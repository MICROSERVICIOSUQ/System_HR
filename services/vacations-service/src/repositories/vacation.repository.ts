import prisma from '../config/db.ts';
import { ESTADOS_ACTIVOS, periodosSolapados } from '../domain/vacation-rules.ts';
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
export type VacationBalanceSortField =
  | 'employeeId'
  | 'nombre'
  | 'apellido'
  | 'email'
  | 'departamentoId'
  | 'diasDisponibles'
  | 'diasTomados'
  | 'estadoEmpleado'
  | 'updatedAt';
type VacationBalanceRecord = Awaited<ReturnType<typeof prisma.vacationBalance.findMany>>[number];

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
  async findAll(estado?: EstadoFiltro): Promise<VacationBalanceRecord[]>;
  async findAll(
    estado?: EstadoFiltro,
    pagination?: {
      page: number;
      limit: number;
      sortBy: VacationBalanceSortField;
      order: 'asc' | 'desc';
    },
  ): Promise<{ total: number; data: VacationBalanceRecord[] }>;
  async findAll(
    estado?: EstadoFiltro,
    pagination?: {
      page: number;
      limit: number;
      sortBy: VacationBalanceSortField;
      order: 'asc' | 'desc';
    },
  ): Promise<VacationBalanceRecord[] | { total: number; data: VacationBalanceRecord[] }> {
    const where = estado ? { estadoEmpleado: estado } : undefined;
    if (!pagination) {
      return prisma.vacationBalance.findMany({
        where,
        orderBy: { updatedAt: 'desc' },
      });
    }

    return prisma.$transaction([
      prisma.vacationBalance.count({ where }),
      prisma.vacationBalance.findMany({
        where,
        skip: (pagination.page - 1) * pagination.limit,
        take: pagination.limit,
        orderBy: { [pagination.sortBy]: pagination.order },
      }),
    ]).then(([total, data]) => ({ total, data }));
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

  // ── Scheduler: transiciones por paso del tiempo ────────────────────────────

  /** Períodos PROGRAMADA cuya fechaInicio ya llegó. */
  async findPeriodsToStart(hasta: Date) {
    return prisma.vacationPeriod.findMany({
      where: {
        estado: 'PROGRAMADA',
        fechaInicio: { lte: hasta },
      },
      orderBy: { fechaInicio: 'asc' },
    });
  }

  /** Finalizar al llegar el día de regreso, no al día siguiente. */
  async findPeriodsToFinish(inicioDelDia: Date) {
    return prisma.vacationPeriod.findMany({
      where: {
        estado: 'EN_CURSO',
        fechaFin: { lte: inicioDelDia },
      },
      orderBy: { fechaFin: 'asc' },
    });
  }

  /**
   * Cambia el estado de un período solo si sigue en el estado esperado.
   *
   * Es un compare-and-set: si dos ejecuciones del scheduler se solapan, la
   * segunda obtiene {@code count === 0} y no vuelve a publicar el evento. Protege
   * dentro de un mismo proceso; la coordinación entre varias instancias del
   * servicio es la limitación documentada que resolverá ShedLock.
   */
  async transitionPeriodState(
    id: string,
    estadoEsperado: string,
    estadoNuevo: string
  ): Promise<boolean> {
    const { count } = await prisma.vacationPeriod.updateMany({
      where: { id, estado: estadoEsperado },
      data: { estado: estadoNuevo },
    });
    return count === 1;
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
  /**
   * Períodos activos del empleado que chocan con el rango propuesto.
   *
   * La base de datos filtra lo que depende de ella (el empleado y el estado) y el
   * predicado de solapamiento se aplica con la regla de dominio
   * {@link periodosSolapados}. Así esa regla tiene un único dueño y se puede
   * verificar con una tabla de casos borde sin levantar PostgreSQL.
   *
   * El conjunto que se trae a memoria está acotado por la realidad del negocio:
   * son los períodos PROGRAMADA o EN_CURSO de un solo empleado, un puñado de filas.
   */
  async findOverlappingPeriods(
    empleadoId: string,
    fechaInicio: Date,
    fechaFin: Date,
    excludePeriodId?: string
  ) {
    const activos = await prisma.vacationPeriod.findMany({
      where: {
        empleadoId,
        estado: { in: [...ESTADOS_ACTIVOS] },
        ...(excludePeriodId && { id: { not: excludePeriodId } }),
      },
    });

    return periodosSolapados(fechaInicio, fechaFin, activos);
  }
}
