import type { Request, Response, NextFunction } from 'express';
import { ProfileService } from '../services/profile.service.ts';
import { ProfileError } from '../errors/profile.errors.ts';

export class ProfileController {
  constructor(private readonly service = new ProfileService()) {}

  getHealth = (_req: Request, res: Response) => {
    return res.json({ status: 'ok', service: 'profiles' });
  };

  /**
   * GET /profiles
   * GET /profiles?estado=ACTIVO
   * GET /profiles?estado=RETIRADO
   */
  getAll = async (req: Request, res: Response, next: NextFunction) => {
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
            page: this.parsePositiveInteger(req.query.page, 'page', 1),
            limit: this.parseLimit(req.query.limit ?? req.query.size),
            sortBy: this.parseSortBy(req.query.sortBy),
            order: this.parseOrder(req.query.order),
          }
        : undefined;

      const profiles = await this.service.getAll(estado, pagination);
      return res.json(profiles);
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /profiles/:employeeId
   */
  getByEmployeeId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const employeeId = Array.isArray(req.params.employeeId)
        ? req.params.employeeId[0]
        : req.params.employeeId;

      const profile = await this.service.getByEmployeeId(employeeId);
      if (!profile) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Perfil con employeeId '${employeeId}' no encontrado`,
        });
      }

      return res.json(profile);
    } catch (error) {
      return next(error);
    }
  };

  create = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const profile = await this.service.create({
        ...req.body,
        lastEventId: this.getIdempotencyKey(req),
      });
      return res.status(201).json(profile);
    } catch (error) {
      return next(error);
    }
  };

  update = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const employeeId = this.getEmployeeId(req);
      const profile = await this.service.update(employeeId, {
        ...req.body,
        lastEventId: this.getIdempotencyKey(req),
      });
      return res.json(profile);
    } catch (error) {
      return next(error);
    }
  };

  retire = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const profile = await this.service.retire(
        this.getEmployeeId(req),
        this.getIdempotencyKey(req),
      );
      return res.json(profile);
    } catch (error) {
      return next(error);
    }
  };

  private getEmployeeId(req: Request): string {
    const employeeId = Array.isArray(req.params.employeeId)
      ? req.params.employeeId[0]
      : req.params.employeeId;
    if (!employeeId?.trim()) throw new ProfileError(400, 'employeeId es obligatorio');
    return employeeId.trim();
  }

  private getIdempotencyKey(req: Request): string | undefined {
    const value = req.header('Idempotency-Key');
    return value?.trim() || undefined;
  }

  private parsePositiveInteger(
    value: unknown,
    name: string,
    defaultValue: number,
  ): number {
    if (value === undefined) return defaultValue;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1) {
      throw new ProfileError(400, `${name} debe ser un entero positivo`);
    }
    return parsed;
  }

  private parseLimit(value: unknown): number {
    const limit = this.parsePositiveInteger(value, 'limit', 10);
    if (limit > 100) throw new ProfileError(400, 'limit no puede ser mayor que 100');
    return limit;
  }

  private parseSortBy(value: unknown): 'employeeId' | 'nombre' | 'apellido' | 'email' | 'cargo' | 'departamentoId' | 'fechaIngreso' | 'estado' | 'fechaRetiro' | 'createdAt' | 'updatedAt' {
    const selected = Array.isArray(value) ? value[0] : value;
    const allowed = ['employeeId', 'nombre', 'apellido', 'email', 'cargo', 'departamentoId', 'fechaIngreso', 'estado', 'fechaRetiro', 'createdAt', 'updatedAt'] as const;
    if (selected === undefined) return 'updatedAt';
    if (!allowed.includes(String(selected) as typeof allowed[number])) {
      throw new ProfileError(400, `sortBy no permitido. Valores: ${allowed.join(', ')}`);
    }
    return String(selected) as typeof allowed[number];
  }

  private parseOrder(value: unknown): 'asc' | 'desc' {
    const selected = String(Array.isArray(value) ? value[0] : value ?? 'DESC').toUpperCase();
    if (selected !== 'ASC' && selected !== 'DESC') {
      throw new ProfileError(400, 'order debe ser ASC o DESC');
    }
    return selected.toLowerCase() as 'asc' | 'desc';
  }
}
