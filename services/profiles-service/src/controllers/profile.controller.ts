import type { Request, Response, NextFunction } from 'express';
import { ProfileService } from '../services/profile.service.ts';
import { ProfileError } from '../errors/profile.errors.ts';
import { presentarPerfil, presentarPerfiles } from '../mappers/profile.mapper.ts';
import { CAMPOS_ORDENABLES, type CampoOrdenable } from '../repositories/profile.repository.ts';

const CAMPOS_EDITABLES: readonly (keyof DatosPerfilEditable)[] = [
  'telefono',
  'direccion',
  'ciudad',
  'biografia',
];

type DatosPerfilEditable = {
  telefono?: string | null;
  direccion?: string | null;
  ciudad?: string | null;
  biografia?: string | null;
};

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
      this.validarPropietario(req, empleadoId);
      const datos = this.filtrarCamposEditables(req.body);
      const profile = await this.service.update(empleadoId, {
        ...datos,
        lastEventId: this.getIdempotencyKey(req),
      });
      return res.json(presentarPerfil(profile as any));
    } catch (error) {
      return next(error);
    }
  };

  retire = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const empleadoId = this.getEmpleadoId(req);
      this.validarPropietario(req, empleadoId);
      const profile = await this.service.retire(
        empleadoId,
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

  private validarPropietario(req: Request, empleadoId: string): void {
    const usuarioAutenticado = req.header('X-Auth-User');
    const rol = req.header('X-Auth-Role');
    if (
      usuarioAutenticado !== undefined &&
      usuarioAutenticado.trim() !== empleadoId &&
      rol !== 'ADMIN'
    ) {
      throw new ProfileError(403, 'No tiene permiso para modificar este perfil');
    }
  }

  private filtrarCamposEditables(body: unknown): DatosPerfilEditable {
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return {};
    }

    const source = body as Record<string, unknown>;
    const datos: DatosPerfilEditable = {};
    for (const campo of CAMPOS_EDITABLES) {
      if (!Object.hasOwn(source, campo) || source[campo] === undefined) continue;
      const value = source[campo];
      if (value !== null && typeof value !== 'string') {
        throw new ProfileError(400, `${campo} debe ser texto o null`);
      }
      datos[campo] = value;
    }
    return datos;
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

  /**
   * Valida ?sortBy contra la lista blanca del repositorio.
   *
   * La lista se importa, no se copia: estaba duplicada aqui como un literal, y
   * dos listas que hay que mantener iguales acaban siendo distintas. Ahora
   * CAMPOS_ORDENABLES es la unica fuente, y de ella salen tambien el enum del
   * contrato OpenAPI y el orderBy de la consulta.
   */
  private parseSortBy(value: unknown): CampoOrdenable {
    const selected = Array.isArray(value) ? value[0] : value;
    if (selected === undefined) return 'updatedAt';
    if (!CAMPOS_ORDENABLES.includes(String(selected) as CampoOrdenable)) {
      throw new ProfileError(
        400,
        `sortBy no permitido. Valores: ${CAMPOS_ORDENABLES.join(', ')}`,
      );
    }
    return String(selected) as CampoOrdenable;
  }

  private parseOrder(value: unknown): 'asc' | 'desc' {
    const selected = String(Array.isArray(value) ? value[0] : value ?? 'DESC').toUpperCase();
    if (selected !== 'ASC' && selected !== 'DESC') {
      throw new ProfileError(400, 'order debe ser ASC o DESC');
    }
    return selected.toLowerCase() as 'asc' | 'desc';
  }
}
