import {
  VacationRepository,
  EstadoFiltro,
  type VacationPeriodFilters,
  type VacationPeriodSortField,
  type VacationBalanceSortField,
} from '../repositories/vacation.repository.ts';
import { RabbitMQPublisher } from '../messaging/rabbitmq.publisher.ts';
import type { Prisma } from '../generated/prisma/client.ts';
import type { PaginatedResponse } from '../types/pagination.ts';

export interface EventEnvelope {
  id: string;
  type: string;
  version?: string;
  occurredAt?: string;
  producer?: string;
  data: {
    id: string;
    nombre?: string;
    apellido?: string;
    email?: string;
    departamentoId?: string;
    [key: string]: any;
  };
}

export type ProcessingResult = 'CREATED' | 'UPDATED' | 'RETIRED' | 'DUPLICATE' | 'IGNORED' | 'ERROR';

const ALLOWED_EVENTS = ['empleado.creado', 'empleado.actualizado', 'empleado.retirado'] as const;

const VACATION_PERIOD_SORT_FIELDS = [
  'id',
  'empleadoId',
  'fechaInicio',
  'fechaFin',
  'estado',
  'fechaCreacion',
] as const satisfies readonly VacationPeriodSortField[];

const isVacationPeriodSortField = (
  value: string | undefined
): value is VacationPeriodSortField =>
  value !== undefined && VACATION_PERIOD_SORT_FIELDS.some((field) => field === value);

// ── DTOs de período ─────────────────────────────────────────────────────────

export interface CreatePeriodDto {
  empleadoId: string;
  fechaInicio: string; // ISO date string (YYYY-MM-DD)
  fechaFin: string;    // ISO date string (YYYY-MM-DD)
}

export interface VacationPeriodResult {
  id: string;
  empleadoId: string;
  fechaInicio: string;
  fechaFin: string;
  estado: string;
  fechaCreacion: string;
}

export class ValidationError extends Error {
  constructor(
    message: string,
    public readonly statusCode: number = 400
  ) {
    super(message);
    this.name = 'ValidationError';
  }
}

export class VacationService {
  private readonly publisher: RabbitMQPublisher;

  constructor(
    private readonly repository = new VacationRepository(),
    publisher?: RabbitMQPublisher
  ) {
    this.publisher = publisher ?? new RabbitMQPublisher();
  }

  // ── Consultas VacationBalance (Read Model) ──────────────────────────────────

  async getAll(
    estado?: string,
    pagination?: {
      page: number;
      limit: number;
      sortBy: VacationBalanceSortField;
      order: 'asc' | 'desc';
    },
  ) {
    const estadoFiltro = this.parseEstado(estado);
    const result = await this.repository.findAll(estadoFiltro, pagination);
    if (!pagination) return result;

    return {
      data: result.data,
      meta: {
        total: result.total,
        page: pagination.page,
        limit: pagination.limit,
        totalPages: Math.ceil(result.total / pagination.limit),
      },
    };
  }

  async getByEmployeeId(employeeId: string) {
    return this.repository.findByEmployeeId(employeeId);
  }

  // ── CRUD VacationPeriod (Productor) ─────────────────────────────────────────

  /**
   * Programa un nuevo período de vacaciones aplicando las 4 validaciones de negocio.
   * Si todo es válido, persiste el período y publica vacaciones.programadas en RabbitMQ.
   */
  async createPeriod(
    dto: CreatePeriodDto,
    correlationId?: string
  ): Promise<VacationPeriodResult> {
    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);

    const inicio = this.parseDate(dto.fechaInicio, 'fechaInicio');
    const fin = this.parseDate(dto.fechaFin, 'fechaFin');

    // Validación 1: fechaFin debe ser posterior a fechaInicio
    if (fin <= inicio) {
      throw new ValidationError(
        `fechaFin (${dto.fechaFin}) debe ser posterior a fechaInicio (${dto.fechaInicio})`
      );
    }

    // Validación 2: fechaInicio no puede ser en el pasado
    if (inicio < today) {
      throw new ValidationError(
        `fechaInicio (${dto.fechaInicio}) no puede ser una fecha pasada`
      );
    }

    // Validación 3: empleado debe existir en la réplica local (VacationBalance)
    const balance = await this.repository.findByEmployeeId(dto.empleadoId);
    if (!balance) {
      throw new ValidationError(
        `Empleado con id '${dto.empleadoId}' no encontrado. Debe existir en el sistema de vacaciones.`,
        404
      );
    }

    // Validación 4: no debe solaparse con períodos PROGRAMADA o EN_CURSO del mismo empleado
    const overlaps = await this.repository.findOverlappingPeriods(
      dto.empleadoId,
      inicio,
      fin
    );
    if (overlaps.length > 0) {
      const overlap = overlaps[0];
      const iniStr = overlap.fechaInicio instanceof Date
        ? overlap.fechaInicio.toISOString().split('T')[0]
        : String(overlap.fechaInicio);
      const finStr = overlap.fechaFin instanceof Date
        ? overlap.fechaFin.toISOString().split('T')[0]
        : String(overlap.fechaFin);
      throw new ValidationError(
        `El período solicitado se solapa con uno existente (${iniStr} → ${finStr}, estado: ${overlap.estado})`
      );
    }

    // Persistir el período
    const period = await this.repository.createPeriod({
      empleadoId: dto.empleadoId,
      fechaInicio: inicio,
      fechaFin: fin,
      estado: 'PROGRAMADA',
    });

    const result = this.mapPeriod(period);

    // Publicar evento vacaciones.programadas en RabbitMQ (fire-and-forget, no bloquea)
    this.publisher
      .publishVacacionesProgramadas({
        id: result.id,
        empleadoId: result.empleadoId,
        fechaInicio: result.fechaInicio,
        fechaFin: result.fechaFin,
        estado: result.estado,
        fechaCreacion: result.fechaCreacion,
      }, correlationId)
      .catch((err) => {
        console.error(
          `[VACATIONS] [WARN] No se pudo publicar evento para período ${result.id}:`,
          err.message
        );
      });

    return result;
  }

  async getAllPeriods(
    filters: VacationPeriodFilters,
    pagination: {
      page?: number;
      limit?: number;
      sortBy?: string;
      order?: string;
    } = {}
  ): Promise<PaginatedResponse<VacationPeriodResult>> {
    const requestedPage = pagination.page;
    const page = requestedPage !== undefined &&
      Number.isSafeInteger(requestedPage) &&
      requestedPage > 0
      ? requestedPage
      : 1;
    const requestedLimit = pagination.limit;
    const validLimit = requestedLimit !== undefined &&
      Number.isSafeInteger(requestedLimit) &&
      requestedLimit > 0
      ? requestedLimit
      : 10;
    const limit = Math.min(validLimit, 100);
    const sortBy = isVacationPeriodSortField(pagination.sortBy)
      ? pagination.sortBy
      : 'fechaCreacion';
    const order = pagination.order?.toLowerCase() === 'asc' ? 'asc' : 'desc';

    const { total, periods } = await this.repository.findAllPeriods(filters, {
      page,
      limit,
      sortBy,
      order,
    });
    const totalPages = Math.ceil(total / limit);

    return {
      data: periods.map(this.mapPeriod),
      meta: {
        total,
        page,
        limit,
        totalPages,
        hasNextPage: page < totalPages,
        hasPrevPage: page > 1,
      },
    };
  }

  async updatePeriod(
    id: string,
    dto: { fechaInicio?: string; fechaFin?: string },
    correlationId?: string
  ): Promise<VacationPeriodResult> {
    const existing = await this.repository.findPeriodById(id);
    if (!existing) {
      throw new ValidationError('Período no encontrado', 404);
    }

    if (existing.estado !== 'PROGRAMADA') {
      throw new ValidationError('Solo se pueden reprogramar vacaciones en estado PROGRAMADA');
    }

    if (dto.fechaInicio === undefined && dto.fechaFin === undefined) {
      throw new ValidationError('Debe proporcionar fechaInicio o fechaFin para reprogramar');
    }

    const existingStart = existing.fechaInicio.toISOString().split('T')[0];
    const existingEnd = existing.fechaFin.toISOString().split('T')[0];
    const fechaInicioInput = dto.fechaInicio ?? existingStart;
    const fechaFinInput = dto.fechaFin ?? existingEnd;
    const inicio = this.parseDate(fechaInicioInput, 'fechaInicio');
    const fin = this.parseDate(fechaFinInput, 'fechaFin');

    if (fin <= inicio) {
      throw new ValidationError(
        `fechaFin (${fechaFinInput}) debe ser posterior a fechaInicio (${fechaInicioInput})`
      );
    }

    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);
    if (inicio < today) {
      throw new ValidationError(`fechaInicio (${fechaInicioInput}) no puede ser una fecha pasada`);
    }

    const overlaps = await this.repository.findOverlappingPeriods(
      existing.empleadoId,
      inicio,
      fin,
      id
    );
    if (overlaps.length > 0) {
      const conflict = overlaps[0];
      throw new ValidationError(
        `El período solicitado se solapa con uno existente: ${JSON.stringify({
          id: conflict.id,
          empleadoId: conflict.empleadoId,
          fechaInicio: this.mapPeriod(conflict).fechaInicio,
          fechaFin: this.mapPeriod(conflict).fechaFin,
          estado: conflict.estado,
        })}`
      );
    }

    const updated = await this.repository.updatePeriod(id, {
      fechaInicio: inicio,
      fechaFin: fin,
    });
    const result = this.mapPeriod(updated);

    this.publisher
      .publishVacacionesReprogramadas({
        id: result.id,
        empleadoId: result.empleadoId,
        fechaInicio: result.fechaInicio,
        fechaFin: result.fechaFin,
        estado: result.estado,
        fechaCreacion: result.fechaCreacion,
      }, correlationId)
      .catch((err) => {
        console.error(
          `[VACATIONS] [WARN] No se pudo publicar evento de reprogramación para período ${result.id}:`,
          err.message
        );
      });

    return result;
  }

  async getPeriodById(id: string): Promise<VacationPeriodResult | null> {
    const period = await this.repository.findPeriodById(id);
    return period ? this.mapPeriod(period) : null;
  }

  async deletePeriod(id: string): Promise<VacationPeriodResult> {
    const existing = await this.repository.findPeriodById(id);
    if (!existing) {
      throw new ValidationError(`Período de vacaciones con id '${id}' no encontrado`, 404);
    }

    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);
    const fechaInicio = new Date(existing.fechaInicio.getTime());
    fechaInicio.setUTCHours(0, 0, 0, 0);

    if (fechaInicio <= today) {
      throw new ValidationError(
        'No es posible cancelar un período de vacaciones que ya ha iniciado o finalizado'
      );
    }

    const cancelled = await this.repository.cancelPeriod(id);
    return this.mapPeriod(cancelled);
  }

  // ── Procesamiento de eventos RabbitMQ (CQRS Consumer) ──────────────────────

  async processEvent(envelope: EventEnvelope): Promise<ProcessingResult> {
    const { id: eventId, type: eventType, data } = envelope;
    const employeeId = data?.id ?? 'UNKNOWN';

    console.log(
      `[VACATIONS] Mensaje recibido | UUID: ${eventId} | Evento: ${eventType} | Empleado: ${employeeId}`
    );

    // Filtrar eventos no gestionados por este microservicio
    if (!ALLOWED_EVENTS.includes(eventType as any)) {
      console.log(
        `[VACATIONS] Evento ignorado | UUID: ${eventId} | Tipo '${eventType}' no aplica para vacaciones`
      );
      return 'IGNORED';
    }

    try {
      const processed = await this.repository.processOnce(eventId, async (transaction) => {
        switch (eventType) {
          case 'empleado.creado':
            return this.handleEmpleadoCreado(eventId, employeeId, data, transaction);

          case 'empleado.actualizado':
            return this.handleEmpleadoActualizado(eventId, employeeId, data, transaction);

          case 'empleado.retirado':
            return this.handleEmpleadoRetirado(eventId, employeeId, data, transaction);

          default:
            return 'IGNORED';
        }
      });

      if (processed.duplicate) {
        return 'DUPLICATE';
      }
      return processed.result;
    } catch (error: any) {
      if (error?.code === 'P2002') {
        if (await this.repository.isEventProcessed(eventId)) {
          return 'DUPLICATE';
        }
      }

      console.error(
        `[VACATIONS] [ERROR] Excepción procesando evento | UUID: ${eventId} | Evento: ${eventType} | Empleado: ${employeeId}:`,
        error
      );
      throw error;
    }
  }

  // ── Manejadores de cada tipo de evento ─────────────────────────────────────

  private async handleEmpleadoCreado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data'],
    transaction: Prisma.TransactionClient
  ): Promise<ProcessingResult> {
    await this.repository.create({
      employeeId,
      nombre: data.nombre ?? '',
      apellido: data.apellido ?? '',
      email: data.email ?? '',
      departamentoId: data.departamentoId ?? '',
      diasDisponibles: 15,
      diasTomados: 0,
      estadoEmpleado: 'ACTIVO',
      lastEventId: eventId,
    }, transaction);

    console.log(
      `[VACATIONS] [CREADO] Registro de vacaciones inicializado | UUID: ${eventId} | Empleado: ${employeeId} | ` +
        `Nombre: ${data.nombre} ${data.apellido} | Disponibles: 15 | Tomados: 0 | Estado: ACTIVO`
    );
    return 'CREATED';
  }

  private async handleEmpleadoActualizado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data'],
    transaction: Prisma.TransactionClient
  ): Promise<ProcessingResult> {
    const existing = await transaction.vacationBalance.findUnique({
      where: { employeeId },
    });

    if (!existing) {
      console.warn(
        `[VACATIONS] [ACTUALIZADO] Empleado no encontrado previamente | UUID: ${eventId} | Empleado: ${employeeId}. ` +
          `Inicializando registro de vacaciones de forma defensiva.`
      );
      await this.repository.create({
        employeeId,
        nombre: data.nombre ?? '',
        apellido: data.apellido ?? '',
        email: data.email ?? '',
        departamentoId: data.departamentoId ?? '',
        diasDisponibles: 15,
        diasTomados: 0,
        estadoEmpleado: 'ACTIVO',
        lastEventId: eventId,
      }, transaction);
      return 'CREATED';
    }

    // Actualizar únicamente nombre, apellido, email, departamentoId. NO modificar días de vacaciones.
    await this.repository.updateInfo(employeeId, {
      nombre: data.nombre,
      apellido: data.apellido,
      email: data.email,
      departamentoId: data.departamentoId,
      lastEventId: eventId,
    }, transaction);

    console.log(
      `[VACATIONS] [ACTUALIZADO] Información de empleado actualizada (días de vacaciones intactos) | ` +
        `UUID: ${eventId} | Empleado: ${employeeId} | Nombre: ${data.nombre} ${data.apellido}`
    );
    return 'UPDATED';
  }

  private async handleEmpleadoRetirado(
    eventId: string,
    employeeId: string,
    _data: EventEnvelope['data'],
    transaction: Prisma.TransactionClient
  ): Promise<ProcessingResult> {
    const cancelled = await this.repository.cancelPendingVacations(transaction, employeeId);
    const existing = await transaction.vacationBalance.findUnique({
      where: { employeeId },
    });

    if (!existing) {
      console.warn(
        `[VACATIONS] [RETIRADO] Réplica de empleado no encontrada | UUID: ${eventId} | ` +
          `Empleado: ${employeeId}. Vacaciones PROGRAMADA canceladas: ${cancelled.count}.`
      );
      return 'IGNORED';
    }

    // Actualizar estadoEmpleado = RETIRADO, NO eliminar el registro, conservar el saldo histórico.
    await this.repository.retire(employeeId, {
      lastEventId: eventId,
    }, transaction);

    console.log(
      `[VACATIONS] [RETIRADO] Empleado marcado como RETIRADO (saldo histórico conservado) | ` +
        `UUID: ${eventId} | Empleado: ${employeeId} | Disponibles: ${existing.diasDisponibles} | ` +
        `Tomados: ${existing.diasTomados} | Vacaciones PROGRAMADA canceladas: ${cancelled.count}`
    );
    return 'RETIRED';
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private parseEstado(estado?: string): EstadoFiltro | undefined {
    if (estado === 'ACTIVO' || estado === 'RETIRADO') return estado;
    return undefined;
  }

  /** Parsea una string YYYY-MM-DD a Date (midnight UTC) */
  private parseDate(dateStr: string, field: string): Date {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
      throw new ValidationError(
        `El campo '${field}' debe tener formato YYYY-MM-DD, recibido: '${dateStr}'`
      );
    }
    const d = new Date(dateStr + 'T00:00:00.000Z');
    if (isNaN(d.getTime())) {
      throw new ValidationError(`El campo '${field}' no es una fecha válida: '${dateStr}'`);
    }
    return d;
  }

  private mapPeriod(p: any): VacationPeriodResult {
    return {
      id: p.id,
      empleadoId: p.empleadoId,
      fechaInicio: p.fechaInicio instanceof Date
        ? p.fechaInicio.toISOString().split('T')[0]
        : String(p.fechaInicio).split('T')[0],
      fechaFin: p.fechaFin instanceof Date
        ? p.fechaFin.toISOString().split('T')[0]
        : String(p.fechaFin).split('T')[0],
      estado: p.estado,
      fechaCreacion: p.fechaCreacion instanceof Date
        ? p.fechaCreacion.toISOString()
        : String(p.fechaCreacion),
    };
  }
}
