import { health } from '../observability/health.ts';
import type { Request, Response, NextFunction } from 'express';
import { VacationService, ValidationError } from '../services/vacation.service.ts';
import type { VacationBalanceSortField, VacationPeriodSortField } from '../repositories/vacation.repository.ts';

const firstQueryValue = (value: unknown): string | undefined => {
  const selected = Array.isArray(value) ? value[0] : value;
  return selected === undefined ? undefined : String(selected);
};

const requiredPositiveIntegerQuery = (
  value: unknown,
  name: string,
  defaultValue: number,
): number => {
  const selected = firstQueryValue(value);
  if (selected === undefined) return defaultValue;
  if (!/^\d+$/.test(selected)) {
    throw new ValidationError(`${name} debe ser un entero positivo`);
  }
  const parsed = Number(selected);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new ValidationError(`${name} debe ser un entero positivo`);
  }
  return parsed;
};

const parseLimit = (value: unknown): number => {
  const limit = requiredPositiveIntegerQuery(value, 'limit', 10);
  if (limit > 100) throw new ValidationError('limit no puede ser mayor que 100');
  return limit;
};

const parseOrder = (value: unknown): 'asc' | 'desc' => {
  const selected = (firstQueryValue(value) ?? 'DESC').toUpperCase();
  if (selected !== 'ASC' && selected !== 'DESC') {
    throw new ValidationError('order debe ser ASC o DESC');
  }
  return selected.toLowerCase() as 'asc' | 'desc';
};

const parseSortBy = <T extends string>(
  value: unknown,
  allowed: readonly T[],
  defaultValue: T,
): T => {
  const selected = firstQueryValue(value);
  if (selected === undefined) return defaultValue;
  if (!allowed.includes(selected as T)) {
    throw new ValidationError(`sortBy no permitido. Valores: ${allowed.join(', ')}`);
  }
  return selected as T;
};

export class VacationController {
  constructor(private readonly service = new VacationService()) {}

  getHealth = async (_req: Request, res: Response) => {
    const result = await health();
    return res.status(result.status === 'UP' ? 200 : 503).json(result);
  };

  // ── VacationBalance: Saldos CQRS (Read Model) ─────────────────────────────

  /**
   * GET /vacations/balances
   * GET /vacations/balances?estado=ACTIVO
   * GET /vacations/balances?estado=RETIRADO
   */
  getAllBalances = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const estado = Array.isArray(req.query.estado)
        ? String(req.query.estado[0])
        : req.query.estado
        ? String(req.query.estado)
        : undefined;

      const hasPagination = ['page', 'limit', 'size', 'sortBy', 'order'].some(
        (key) => req.query[key] !== undefined,
      );
      const pagination = hasPagination
        ? {
            page: requiredPositiveIntegerQuery(req.query.page, 'page', 1),
            limit: parseLimit(req.query.limit ?? req.query.size),
            sortBy: parseSortBy<VacationBalanceSortField>(
              req.query.sortBy,
              ['employeeId', 'nombre', 'apellido', 'email', 'departamentoId', 'diasDisponibles', 'diasTomados', 'estadoEmpleado', 'updatedAt'],
              'updatedAt',
            ),
            order: parseOrder(req.query.order),
          }
        : undefined;

      const balances = await this.service.getAll(estado, pagination);
      return res.json(balances);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /vacations/balances/:employeeId
   */
  getBalanceByEmployeeId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const employeeId = String(req.params.employeeId);
      const balance = await this.service.getByEmployeeId(employeeId);
      if (!balance) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Saldo de vacaciones para employeeId '${employeeId}' no encontrado`,
        });
      }
      return res.json(balance);
    } catch (error) {
      return next(error);
    }
  };

  // ── VacationPeriod: Períodos programados (Productor) ──────────────────────

  /**
   * POST /vacations
   * Body: { empleadoId, fechaInicio (YYYY-MM-DD), fechaFin (YYYY-MM-DD) }
   * Valida: fechas coherentes, no pasado, empleado existe, no solapamiento.
   * Publica vacaciones.programadas en RabbitMQ al completar.
   */
  createPeriod = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { empleadoId, fechaInicio, fechaFin } = req.body;

      if (!empleadoId || !fechaInicio || !fechaFin) {
        return res.status(400).json({
          status: 400,
          error: 'Bad Request',
          message: 'Los campos empleadoId, fechaInicio y fechaFin son obligatorios',
        });
      }

      const period = await this.service.createPeriod(
        { empleadoId, fechaInicio, fechaFin },
        res.locals.correlationId
      );
      return res.status(201).json(period);
    } catch (error) {
      if (error instanceof ValidationError) {
        return res.status(error.statusCode).json({
          status: error.statusCode,
          error: error.statusCode === 404 ? 'Not Found' : 'Bad Request',
          message: error.message,
        });
      }
      return next(error);
    }
  };

  /**
   * GET /vacations
   * GET /vacations?empleadoId=EMP-001
   */
  getAllPeriods = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const hasPagination = ['page', 'limit', 'sortBy', 'order'].some(
        (key) => req.query[key] !== undefined,
      );
      const periods = await this.service.getAllPeriods(
        {
          empleadoId: firstQueryValue(req.query.empleadoId),
          estado: firstQueryValue(req.query.estado),
        },
        {
          page: hasPagination
            ? requiredPositiveIntegerQuery(req.query.page, 'page', 1)
            : undefined,
          limit: hasPagination ? parseLimit(req.query.limit) : undefined,
          sortBy: hasPagination
            ? parseSortBy<VacationPeriodSortField>(
                req.query.sortBy,
                ['id', 'empleadoId', 'fechaInicio', 'fechaFin', 'estado', 'fechaCreacion'],
                'fechaCreacion',
              )
            : undefined,
          order: hasPagination ? parseOrder(req.query.order) : undefined,
        }
      );
      return res.json(periods);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * PUT/PATCH /vacations/:id
   * Body: { fechaInicio?, fechaFin? }
   */
  updatePeriod = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const id = String(req.params.id);
      const { fechaInicio, fechaFin } = req.body ?? {};
      const period = await this.service.updatePeriod(
        id,
        { fechaInicio, fechaFin },
        res.locals.correlationId
      );
      return res.json(period);
    } catch (error) {
      if (error instanceof ValidationError) {
        return res.status(error.statusCode).json({
          status: error.statusCode,
          error: error.statusCode === 404 ? 'Not Found' : 'Bad Request',
          message: error.message,
        });
      }
      return next(error);
    }
  };

  /**
   * GET /vacations/:id
   */
  getPeriodById = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const id = String(req.params.id);
      const period = await this.service.getPeriodById(id);
      if (!period) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Período de vacaciones con id '${id}' no encontrado`,
        });
      }
      return res.json(period);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * DELETE /vacations/:id
   */
  deletePeriod = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const id = String(req.params.id);
      const deleted = await this.service.deletePeriod(id);
      return res.json({ message: 'Período de vacaciones cancelado correctamente', data: deleted });
    } catch (error) {
      if (error instanceof ValidationError) {
        return res.status(error.statusCode).json({
          status: error.statusCode,
          error: error.statusCode === 404 ? 'Not Found' : 'Bad Request',
          message: error.message,
        });
      }
      return next(error);
    }
  };

  /**
   * ENDPOINT DE DESARROLLO. Dispara el inicio del período sin esperar la fecha,
   * para poder demostrar el flujo completo en la sustentación. Requiere ADMIN
   * (lo exige el api-gateway, no este servicio).
   */
  forcePeriodStart = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const id = String(req.params.id);
      const period = await this.service.forcePeriodStart(id);
      return res.json({
        message: 'Inicio de período forzado: se publicó vacaciones.iniciadas',
        data: period,
      });
    } catch (error) {
      return this.handle(error, res, next);
    }
  };

  /** ENDPOINT DE DESARROLLO. Contraparte de forcePeriodStart. Requiere ADMIN. */
  forcePeriodFinish = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const id = String(req.params.id);
      const period = await this.service.forcePeriodFinish(id);
      return res.json({
        message: 'Fin de período forzado: se publicó vacaciones.finalizadas',
        data: period,
      });
    } catch (error) {
      return this.handle(error, res, next);
    }
  };

  private handle(error: unknown, res: Response, next: NextFunction) {
    if (error instanceof ValidationError) {
      return res.status(error.statusCode).json({
        status: error.statusCode,
        error: error.statusCode === 404 ? 'Not Found' : 'Bad Request',
        message: error.message,
      });
    }
    return next(error);
  }
}
