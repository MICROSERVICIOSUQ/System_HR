import prisma from '../config/db.ts';

export interface CreateProfileDto {
  empleadoId: string;
  nombre: string;
  apellido: string;
  email: string;
  cargo: string;
  departamentoId: string;
  fechaIngreso: Date;
  estado: string;
  lastEventId: string;
}

export interface UpdateProfileDto {
  nombre?: string;
  apellido?: string;
  email?: string;
  cargo?: string;
  departamentoId?: string;
  estado?: string;
  fechaRetiro?: Date | null;
  // ── Los cinco campos canónicos del perfil ─────────────────────────────────
  // Son los únicos que el propio empleado puede editar sobre su perfil: nombre,
  // cargo o departamento los gobierna employees-service y llegan por evento.
  telefono?: string | null;
  direccion?: string | null;
  ciudad?: string | null;
  biografia?: string | null;
  archivado?: boolean;
  lastEventId: string;
}

export interface RetireProfileDto {
  fechaRetiro: Date;
  lastEventId: string;
}

export type EstadoFiltro = 'ACTIVO' | 'RETIRADO';

/** Campos por los que se puede ordenar el listado. */
export const CAMPOS_ORDENABLES = [
  'empleadoId',
  'nombre',
  'apellido',
  'email',
  'cargo',
  'departamentoId',
  'fechaIngreso',
  'estado',
  'fechaRetiro',
  'fechaCreacion',
  'archivado',
  'updatedAt',
] as const;

export type CampoOrdenable = (typeof CAMPOS_ORDENABLES)[number];

export class ProfileRepository {
  /** Buscar por lastEventId para garantía de idempotencia */
  async findByLastEventId(eventId: string) {
    return prisma.employeeProfile.findUnique({
      where: { lastEventId: eventId },
    });
  }

  /** Buscar perfil por ID de negocio del empleado */
  async findByEmpleadoId(empleadoId: string) {
    return prisma.employeeProfile.findUnique({
      where: { empleadoId },
    });
  }

  /** Listar perfiles, con filtros opcionales por estado y por archivado */
  async findAll(
    estado?: EstadoFiltro,
    pagination?: {
      page: number;
      limit: number;
      sortBy: CampoOrdenable;
      order: 'asc' | 'desc';
    },
    archivado?: boolean,
  ) {
    // El filtro se compone: ?estado=ACTIVO&archivado=false son dos condiciones
    // distintas que pueden coincidir pero no son lo mismo. Un perfil podría
    // archivarse por otro motivo en el futuro sin estar retirado.
    const where: { estado?: EstadoFiltro; archivado?: boolean } = {};
    if (estado !== undefined) where.estado = estado;
    if (archivado !== undefined) where.archivado = archivado;
    const filtro = Object.keys(where).length > 0 ? where : undefined;

    if (!pagination) {
      return prisma.employeeProfile.findMany({
        where: filtro,
        orderBy: { updatedAt: 'desc' },
      });
    }

    return prisma.$transaction([
      prisma.employeeProfile.count({ where: filtro }),
      prisma.employeeProfile.findMany({
        where: filtro,
        skip: (pagination.page - 1) * pagination.limit,
        take: pagination.limit,
        orderBy: { [pagination.sortBy]: pagination.order },
      }),
    ]).then(([total, data]) => ({ total, data }));
  }

  /** Crear un nuevo perfil de empleado */
  async create(data: CreateProfileDto) {
    return prisma.employeeProfile.create({
      data: {
        empleadoId: data.empleadoId,
        nombre: data.nombre,
        apellido: data.apellido,
        email: data.email,
        cargo: data.cargo,
        departamentoId: data.departamentoId,
        fechaIngreso: data.fechaIngreso,
        estado: data.estado,
        lastEventId: data.lastEventId,
      },
    });
  }

  /** Actualizar datos del perfil */
  async update(empleadoId: string, data: UpdateProfileDto) {
    return prisma.employeeProfile.update({
      where: { empleadoId },
      data: {
        ...(data.nombre !== undefined && { nombre: data.nombre }),
        ...(data.apellido !== undefined && { apellido: data.apellido }),
        ...(data.email !== undefined && { email: data.email }),
        ...(data.cargo !== undefined && { cargo: data.cargo }),
        ...(data.departamentoId !== undefined && { departamentoId: data.departamentoId }),
        ...(data.estado !== undefined && { estado: data.estado }),
        ...(data.fechaRetiro !== undefined && { fechaRetiro: data.fechaRetiro }),
        ...(data.telefono !== undefined && { telefono: data.telefono }),
        ...(data.direccion !== undefined && { direccion: data.direccion }),
        ...(data.ciudad !== undefined && { ciudad: data.ciudad }),
        ...(data.biografia !== undefined && { biografia: data.biografia }),
        ...(data.archivado !== undefined && { archivado: data.archivado }),
        lastEventId: data.lastEventId,
      },
    });
  }

  /**
   * Marcar el perfil como RETIRADO y archivarlo (nunca eliminar).
   *
   * El archivado va junto al retiro en la misma escritura: si fueran dos
   * operaciones, un fallo entre ellas dejaría un perfil retirado sin archivar y
   * el filtro ?archivado=false seguiría devolviéndolo.
   */
  async retire(empleadoId: string, data: RetireProfileDto) {
    return prisma.employeeProfile.update({
      where: { empleadoId },
      data: {
        estado: 'RETIRADO',
        fechaRetiro: data.fechaRetiro,
        archivado: true,
        lastEventId: data.lastEventId,
      },
    });
  }
}
