import { VacationRepository, EstadoFiltro } from '../repositories/vacation.repository.ts';
import { RabbitMQPublisher } from '../messaging/rabbitmq.publisher.ts';

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

  async getAll(estado?: string) {
    const estadoFiltro = this.parseEstado(estado);
    return this.repository.findAll(estadoFiltro);
  }

  async getByEmployeeId(employeeId: string) {
    return this.repository.findByEmployeeId(employeeId);
  }

  // ── CRUD VacationPeriod (Productor) ─────────────────────────────────────────

  /**
   * Programa un nuevo período de vacaciones aplicando las 4 validaciones de negocio.
   * Si todo es válido, persiste el período y publica vacaciones.programadas en RabbitMQ.
   */
  async createPeriod(dto: CreatePeriodDto): Promise<VacationPeriodResult> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

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
      })
      .catch((err) => {
        console.error(
          `[VACATIONS] [WARN] No se pudo publicar evento para período ${result.id}:`,
          err.message
        );
      });

    return result;
  }

  async getAllPeriods(empleadoId?: string): Promise<VacationPeriodResult[]> {
    const periods = await this.repository.findAllPeriods(empleadoId);
    return periods.map(this.mapPeriod);
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

    const deleted = await this.repository.deletePeriod(id);
    return this.mapPeriod(deleted);
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

    // ── Idempotencia: Verificar si lastEventId ya existe ───────────────────────
    const existingByEvent = await this.repository.findByLastEventId(eventId);
    if (existingByEvent) {
      console.warn(
        `[VACATIONS] [DUPLICADO] Evento ya procesado anteriormente | UUID: ${eventId} | Evento: ${eventType} | Empleado: ${employeeId}`
      );
      return 'DUPLICATE';
    }

    // ── Aplicar operación según el evento ─────────────────────────────────────
    try {
      switch (eventType) {
        case 'empleado.creado':
          return await this.handleEmpleadoCreado(eventId, employeeId, data);

        case 'empleado.actualizado':
          return await this.handleEmpleadoActualizado(eventId, employeeId, data);

        case 'empleado.retirado':
          return await this.handleEmpleadoRetirado(eventId, employeeId, data);

        default:
          return 'IGNORED';
      }
    } catch (error: any) {
      // Conflicto de restricción UNIQUE (condición de carrera o reentrega concurrente)
      if (error?.code === 'P2002') {
        console.warn(
          `[VACATIONS] [DUPLICADO] Conflicto UNIQUE detectado al procesar | UUID: ${eventId} | Empleado: ${employeeId}`
        );
        return 'DUPLICATE';
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
    data: EventEnvelope['data']
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
    });

    console.log(
      `[VACATIONS] [CREADO] Registro de vacaciones inicializado | UUID: ${eventId} | Empleado: ${employeeId} | ` +
        `Nombre: ${data.nombre} ${data.apellido} | Disponibles: 15 | Tomados: 0 | Estado: ACTIVO`
    );
    return 'CREATED';
  }

  private async handleEmpleadoActualizado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data']
  ): Promise<ProcessingResult> {
    const existing = await this.repository.findByEmployeeId(employeeId);

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
      });
      return 'CREATED';
    }

    // Actualizar únicamente nombre, apellido, email, departamentoId. NO modificar días de vacaciones.
    await this.repository.updateInfo(employeeId, {
      nombre: data.nombre,
      apellido: data.apellido,
      email: data.email,
      departamentoId: data.departamentoId,
      lastEventId: eventId,
    });

    console.log(
      `[VACATIONS] [ACTUALIZADO] Información de empleado actualizada (días de vacaciones intactos) | ` +
        `UUID: ${eventId} | Empleado: ${employeeId} | Nombre: ${data.nombre} ${data.apellido}`
    );
    return 'UPDATED';
  }

  private async handleEmpleadoRetirado(
    eventId: string,
    employeeId: string,
    _data: EventEnvelope['data']
  ): Promise<ProcessingResult> {
    const existing = await this.repository.findByEmployeeId(employeeId);

    if (!existing) {
      console.warn(
        `[VACATIONS] [RETIRADO] Empleado no encontrado para retirar | UUID: ${eventId} | Empleado: ${employeeId}. Ignorando.`
      );
      return 'IGNORED';
    }

    // Actualizar estadoEmpleado = RETIRADO, NO eliminar el registro, conservar el saldo histórico.
    await this.repository.retire(employeeId, {
      lastEventId: eventId,
    });

    console.log(
      `[VACATIONS] [RETIRADO] Empleado marcado como RETIRADO (saldo histórico conservado) | ` +
        `UUID: ${eventId} | Empleado: ${employeeId} | Disponibles: ${existing.diasDisponibles} | Tomados: ${existing.diasTomados}`
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
