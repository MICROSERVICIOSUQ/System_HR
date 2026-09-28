import prisma from '../config/db.ts';

export interface CreateProfileDto {
  employeeId: string;
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
  lastEventId: string;
}

export interface RetireProfileDto {
  fechaRetiro: Date;
  lastEventId: string;
}

export type EstadoFiltro = 'ACTIVO' | 'RETIRADO';

export class ProfileRepository {
  /** Buscar por lastEventId para garantía de idempotencia */
  async findByLastEventId(eventId: string) {
    return prisma.employeeProfile.findUnique({
      where: { lastEventId: eventId },
    });
  }

  /** Buscar perfil por ID de negocio del empleado */
  async findByEmployeeId(employeeId: string) {
    return prisma.employeeProfile.findUnique({
      where: { employeeId },
    });
  }

  /** Listar todos los perfiles, con filtro opcional por estado */
  async findAll(estado?: EstadoFiltro) {
    return prisma.employeeProfile.findMany({
      where: estado ? { estado } : undefined,
      orderBy: { updatedAt: 'desc' },
    });
  }

  /** Crear un nuevo perfil de empleado */
  async create(data: CreateProfileDto) {
    return prisma.employeeProfile.create({
      data: {
        employeeId: data.employeeId,
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

  /** Actualizar datos del perfil (empleado.actualizado) */
  async update(employeeId: string, data: UpdateProfileDto) {
    return prisma.employeeProfile.update({
      where: { employeeId },
      data: {
        ...(data.nombre !== undefined && { nombre: data.nombre }),
        ...(data.apellido !== undefined && { apellido: data.apellido }),
        ...(data.email !== undefined && { email: data.email }),
        ...(data.cargo !== undefined && { cargo: data.cargo }),
        ...(data.departamentoId !== undefined && { departamentoId: data.departamentoId }),
        lastEventId: data.lastEventId,
      },
    });
  }

  /** Marcar el perfil como RETIRADO (nunca eliminar) */
  async retire(employeeId: string, data: RetireProfileDto) {
    return prisma.employeeProfile.update({
      where: { employeeId },
      data: {
        estado: 'RETIRADO',
        fechaRetiro: data.fechaRetiro,
        lastEventId: data.lastEventId,
      },
    });
  }
}
