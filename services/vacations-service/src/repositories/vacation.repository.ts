import prisma from '../config/db.ts';
import type { Prisma } from '../generated/prisma/client.ts';

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

// ── DTOs para VacationPeriod ─────────────────────────────────────────────────

export interface CreateVacationPeriodDto {
  empleadoId: string;
  fechaInicio: Date;
  fechaFin: Date;
  estado?: string;
}

export class VacationRepository {
  async processOnce<T>(
    eventId: string,
    process: (transaction: Prisma.TransactionClient) => Promise<T>
  ): Promise<{ duplicate: true } | { duplicate: false; result: T }> {
    return prisma.$transaction(async (transaction) => {
      const existing = await transaction.processedEvent.findUnique({
        where: { id: eventId },
      });
      if (existing) return { duplicate: true as const };

      const result = await process(transaction);
      await transaction.processedEvent.create({ data: { id: eventId } });
      return { duplicate: false as const, result };
    });
  }

  async isEventProcessed(eventId: string): Promise<boolean> {
    return Boolean(
      await prisma.processedEvent.findUnique({
        where: { id: eventId },
      })
    );
  }

  // ── VacationBalance (CQRS Read Model) ──────────────────────────────────────

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
  async create(data: CreateVacationBalanceDto, transaction?: Prisma.TransactionClient) {
    return (transaction ?? prisma).vacationBalance.create({
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
  async updateInfo(
    employeeId: string,
    data: UpdateVacationInfoDto,
    transaction?: Prisma.TransactionClient
  ) {
    return (transaction ?? prisma).vacationBalance.update({
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
  async retire(
    employeeId: string,
    data: RetireVacationDto,
    transaction?: Prisma.TransactionClient
  ) {
    return (transaction ?? prisma).vacationBalance.update({
      where: { employeeId },
      data: {
        estadoEmpleado: 'RETIRADO',
        lastEventId: data.lastEventId,
      },
    });
  }

  // ── VacationPeriod (Productor / Write Model) ────────────────────────────────

  /** Crear un nuevo período de vacaciones */
  async createPeriod(data: CreateVacationPeriodDto) {
    return prisma.vacationPeriod.create({
      data: {
        empleadoId: data.empleadoId,
        fechaInicio: data.fechaInicio,
        fechaFin: data.fechaFin,
        estado: data.estado ?? 'PROGRAMADA',
      },
    });
  }

  /** Listar todos los períodos. Filtro opcional por empleadoId. */
  async findAllPeriods(empleadoId?: string) {
    return prisma.vacationPeriod.findMany({
      where: empleadoId ? { empleadoId } : undefined,
      orderBy: { fechaCreacion: 'desc' },
    });
  }

  /** Buscar un período por ID */
  async findPeriodById(id: string) {
    return prisma.vacationPeriod.findUnique({
      where: { id },
    });
  }

  async cancelPeriod(id: string) {
    return prisma.vacationPeriod.update({
      where: { id },
      data: { estado: 'CANCELADA' },
    });
  }

  /**
   * Detectar solapamientos con períodos PROGRAMADA o EN_CURSO para un empleado.
   * Un solapamiento existe cuando los rangos se cruzan: inicio1 <= fin2 AND fin1 >= inicio2
   * Excluye opcionalmente un ID (para futura edición).
   */
  async findOverlappingPeriods(
    empleadoId: string,
    fechaInicio: Date,
    fechaFin: Date,
    excludeId?: string
  ) {
    return prisma.vacationPeriod.findMany({
      where: {
        empleadoId,
        estado: { in: ['PROGRAMADA', 'EN_CURSO'] },
        id: excludeId ? { not: excludeId } : undefined,
        AND: [
          { fechaInicio: { lte: fechaFin } },
          { fechaFin: { gte: fechaInicio } },
        ],
      },
    });
  }
}
