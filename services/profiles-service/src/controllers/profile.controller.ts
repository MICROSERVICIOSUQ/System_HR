import type { Request, Response, NextFunction } from 'express';
import { ProfileService } from '../services/profile.service.ts';
import { ProfileError } from '../errors/profile.errors.ts';
import { presentarPerfil, presentarPerfiles } from '../mappers/profile.mapper.ts';
import type { CampoOrdenable } from '../repositories/profile.repository.ts';

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

      // ?archivado=false es la consulta corriente: los perfiles de la gente
      // que sigue en la empresa. Se separa del filtro por estado a proposito:
      // hoy coinciden, pero un perfil podria archivarse por otro motivo sin
      // estar retirado, y entonces las dos consultas dejarian de ser la misma.
      const archivado = this.parseArchivado(req.query.archivado);

      const resultado = await this.service.getAll(estado, pagination, archivado);

      // El alias employeeId se anade aqui, en la frontera HTTP: la columna se
      // renombro a empleadoId pero la respuesta sigue llevando los dos nombres
      // para no romper a ningun cliente.
      if (Array.isArray(resultado)) {
        return res.json(presentarPerfiles(resultado as any));
      }
      return res.json({
        ...resultado,
        data: presentarPerfiles((resultado as any).data),
      });
    } catch (error) {
      return next(error);
    }
  };

  /**
   * GET /profiles/:empleadoId
   */
  getByEmpleadoId = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const empleadoId = Array.isArray(req.params.empleadoId)
        ? req.params.empleadoId[0]
        : req.params.empleadoId;

      const profile = await this.service.getByEmpleadoId(empleadoId);
      if (!profile) {
        return res.status(404).json({
          status: 404,
          error: 'Not Found',
          message: `Perfil con empleadoId '${empleadoId}' no encontrado`,
        });
      }

      return res.json(presentarPerfil(profile as any));
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
      return res.status(201).json(presentarPerfil(profile as any));
    } catch (error) {
      return next(error);
    }
  };

  update = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const empleadoId = this.getEmpleadoId(req);
      const profile = await this.service.update(empleadoId, {
        ...req.body,
        lastEventId: this.getIdempotencyKey(req),
      });
      return res.json(presentarPerfil(profile as any));
    } catch (error) {
      return next(error);
    }
  };

  retire = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const profile = await this.service.retire(
        this.getEmpleadoId(req),
        this.getIdempotencyKey(req),
      );
      return res.json(presentarPerfil(profile as any));
    } catch (error) {
      return next(error);
    }
  };

  private getEmpleadoId(req: Request): string {
    const empleadoId = Array.isArray(req.params.empleadoId)
      ? req.params.empleadoId[0]
      : req.params.empleadoId;
    if (!empleadoId?.trim()) throw new ProfileError(400, 'empleadoId es obligatorio');
    return empleadoId.trim();
  }

  /**
   * Interpreta ?archivado=true|false.
   *
   * Ausente significa "no filtrar", que no es lo mismo que false: sin este
   * matiz, GET /perfiles dejaria de devolver los perfiles archivados y cambiaria
   * el comportamiento de un endpoint ya publicado.
   */
  private parseArchivado(value: unknown): boolean | undefined {
    if (value === undefined) return undefined;
    const texto = String(Array.isArray(value) ? value[0] : value).toLowerCase();
    if (texto === 'true') return true;
    if (texto === 'false') return false;
    throw new ProfileError(400, 'archivado debe ser true o false');
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

  private parseSortBy(value: unknown): CampoOrdenable {
    const selected = Array.isArray(value) ? value[0] : value;
    const allowed = ['empleadoId', 'nombre', 'apellido', 'email', 'cargo', 'departamentoId', 'fechaIngreso', 'estado', 'fechaRetiro', 'fechaCreacion', 'archivado', 'updatedAt'] as const;
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
