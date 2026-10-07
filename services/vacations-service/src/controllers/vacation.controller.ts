import type { Request, Response, NextFunction } from 'express';
import { VacationService, ValidationError } from '../services/vacation.service.ts';

export class VacationController {
  constructor(private readonly service = new VacationService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'vacations' });
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

      const balances = await this.service.getAll(estado);
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
      const employeeId = req.params.employeeId;
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

      const period = await this.service.createPeriod({ empleadoId, fechaInicio, fechaFin });
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
      const empleadoId = req.query.empleadoId ? String(req.query.empleadoId) : undefined;
      const periods = await this.service.getAllPeriods(empleadoId);
      return res.json(periods);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /vacations/:id
   */
  getPeriodById = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
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
      const { id } = req.params;
      const deleted = await this.service.deletePeriod(id);
      return res.json({ message: 'Período de vacaciones eliminado correctamente', data: deleted });
    } catch (error) {
      if (error instanceof ValidationError) {
        return res.status(error.statusCode).json({
          status: error.statusCode,
          error: 'Not Found',
          message: error.message,
        });
      }
      return next(error);
    }
  };
}
