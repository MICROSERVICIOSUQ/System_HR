import prisma from '../config/db.ts';
import type { Prisma } from '../generated/prisma/client.ts';

export type VacationPeriodSortField =
  | 'id'
  | 'empleadoId'
  | 'fechaInicio'
  | 'fechaFin'
  | 'estado'
  | 'fechaCreacion';

export interface VacationPeriodFilters {
  empleadoId?: string;
  estado?: string;
}

export interface VacationPeriodPagination {
  page: number;
  limit: number;
  sortBy: VacationPeriodSortField;
  order: 'asc' | 'desc';
}

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

  /** Listar períodos filtrados con conteo y página en una transacción Prisma. */
  async findAllPeriods(
    filters: VacationPeriodFilters,
    pagination: VacationPeriodPagination
  ) {
    const where: Prisma.VacationPeriodWhereInput = {
      ...(filters.empleadoId && { empleadoId: filters.empleadoId }),
      ...(filters.estado && { estado: filters.estado }),
    };
    const orderBy: Prisma.VacationPeriodOrderByWithRelationInput = {
      [pagination.sortBy]: pagination.order,
    };

    const [total, periods] = await prisma.$transaction([
      prisma.vacationPeriod.count({ where }),
      prisma.vacationPeriod.findMany({
        where,
        skip: (pagination.page - 1) * pagination.limit,
        take: pagination.limit,
        orderBy,
      }),
    ]);

    return { total, periods };
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

  async updatePeriod(id: string, data: { fechaInicio: Date; fechaFin: Date }) {
    return prisma.vacationPeriod.update({
      where: { id },
      data: {
        fechaInicio: data.fechaInicio,
        fechaFin: data.fechaFin,
      },
    });
  }

  async cancelPendingVacations(
    transaction: Prisma.TransactionClient,
    empleadoId: string
  ) {
    return transaction.vacationPeriod.updateMany({
      where: {
        empleadoId,
        estado: 'PROGRAMADA',
      },
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
    excludePeriodId?: string
  ) {
    return prisma.vacationPeriod.findMany({
      where: {
        empleadoId,
        estado: { in: ['PROGRAMADA', 'EN_CURSO'] },
        ...(excludePeriodId && { id: { not: excludePeriodId } }),
        AND: [
          { fechaInicio: { lte: fechaFin } },
          { fechaFin: { gte: fechaInicio } },
        ],
      },
    });
  }
}
