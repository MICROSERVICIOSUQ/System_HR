import prisma from '../config/db.ts';

export interface CreateVacationBalanceDto {
  employeeId: string;
  nombre: string;
  apellido: string;
  email: string;
  departamentoId: string;
  diasDisponibles?: number;
  diasTomados?: number;
  estadoEmpleado?: string;
  lastEventId: string;
}

export interface UpdateVacationInfoDto {
  nombre?: string;
  apellido?: string;
  email?: string;
  departamentoId?: string;
  lastEventId: string;
}

export interface RetireVacationDto {
  lastEventId: string;
}

export type EstadoFiltro = 'ACTIVO' | 'RETIRADO';

export class VacationRepository {
  /** Buscar por lastEventId para garantía de idempotencia */
  async findByLastEventId(eventId: string) {
    return prisma.vacationBalance.findUnique({
      where: { lastEventId: eventId },
    });
  }

  /** Buscar saldo de vacaciones por ID de empleado */
  async findByEmployeeId(employeeId: string) {
    return prisma.vacationBalance.findUnique({
      where: { employeeId },
    });
  }

  /** Listar saldos de vacaciones con filtro opcional por estadoEmpleado */
  async findAll(estado?: EstadoFiltro) {
    return prisma.vacationBalance.findMany({
      where: estado ? { estadoEmpleado: estado } : undefined,
      orderBy: { updatedAt: 'desc' },
    });
  }

  /** Crear un nuevo registro de saldo de vacaciones */
  async create(data: CreateVacationBalanceDto) {
    return prisma.vacationBalance.create({
      data: {
        employeeId: data.employeeId,
        nombre: data.nombre,
        apellido: data.apellido,
        email: data.email,
        departamentoId: data.departamentoId,
        diasDisponibles: data.diasDisponibles ?? 15,
        diasTomados: data.diasTomados ?? 0,
        estadoEmpleado: data.estadoEmpleado ?? 'ACTIVO',
        lastEventId: data.lastEventId,
      },
    });
  }

  /** Actualizar información personal y departamento sin alterar días de vacaciones */
  async updateInfo(employeeId: string, data: UpdateVacationInfoDto) {
    return prisma.vacationBalance.update({
      where: { employeeId },
      data: {
        ...(data.nombre !== undefined && { nombre: data.nombre }),
        ...(data.apellido !== undefined && { apellido: data.apellido }),
        ...(data.email !== undefined && { email: data.email }),
        ...(data.departamentoId !== undefined && { departamentoId: data.departamentoId }),
        lastEventId: data.lastEventId,
      },
    });
  }

  /** Actualizar estado a RETIRADO conservando el saldo histórico (nunca eliminar) */
  async retire(employeeId: string, data: RetireVacationDto) {
    return prisma.vacationBalance.update({
      where: { employeeId },
      data: {
        estadoEmpleado: 'RETIRADO',
        lastEventId: data.lastEventId,
      },
    });
  }
}
