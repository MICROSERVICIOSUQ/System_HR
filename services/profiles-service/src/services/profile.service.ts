import { ProfileRepository, EstadoFiltro } from '../repositories/profile.repository.ts';
import { ProfileError } from '../errors/profile.errors.ts';
import { EmployeeEventPublisher } from '../messaging/employee-event.publisher.ts';
import crypto from 'node:crypto';

// ─── Tipos de eventos ────────────────────────────────────────────────────────

export interface EventEnvelope {
  id: string;
  type: string;
  version: string;
  occurredAt: string;
  producer: string;
  data: {
    id: string;
    nombre?: string;
    apellido?: string;
    email?: string;
    cargo?: string;
    departamentoId?: string;
    fechaIngreso?: string;
    fechaRetiro?: string | null;
    [key: string]: unknown;
  };
}

export type ProcessingResult = 'CREATED' | 'UPDATED' | 'RETIRED' | 'DUPLICATE' | 'IGNORED' | 'ERROR';

// ─── Tipos de eventos soportados ─────────────────────────────────────────────

const ALLOWED_EVENTS = ['empleado.creado', 'empleado.actualizado', 'empleado.retirado'] as const;

// ─── Servicio principal ──────────────────────────────────────────────────────

export class ProfileService {
  constructor(
    private readonly repository = new ProfileRepository(),
    private readonly eventPublisher = new EmployeeEventPublisher(),
  ) {}

  // ── Queries (Read Model) ──────────────────────────────────────────────────

  async getAll(
    estado?: string,
    pagination?: {
      page: number;
      limit: number;
      sortBy: 'employeeId' | 'nombre' | 'apellido' | 'email' | 'cargo' | 'departamentoId' | 'fechaIngreso' | 'estado' | 'fechaRetiro' | 'createdAt' | 'updatedAt';
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

  async create(data: {
    employeeId: string;
    nombre: string;
    apellido: string;
    email: string;
    cargo: string;
    departamentoId: string;
    fechaIngreso: string;
    estado?: string;
    lastEventId?: string;
  }) {
    this.validateRequiredFields(data, [
      'employeeId',
      'nombre',
      'apellido',
      'email',
      'cargo',
      'departamentoId',
      'fechaIngreso',
    ]);
    this.validateEmail(data.email);
    const eventId = data.lastEventId ?? crypto.randomUUID();
    const existingEvent = await this.repository.findByLastEventId(eventId);
    if (existingEvent) return existingEvent;

    const existingProfile = await this.repository.findByEmployeeId(data.employeeId);
    if (existingProfile) {
      throw new ProfileError(409, `Ya existe un perfil para el empleado '${data.employeeId}'`);
    }

    const estado = this.parseEstado(data.estado ?? 'ACTIVO');
    if (!estado) {
      throw new ProfileError(400, 'Estado inválido');
    }
    if (estado !== 'ACTIVO') {
      throw new ProfileError(400, 'Un perfil nuevo debe iniciar en estado ACTIVO');
    }

    return this.repository.create({
      ...data,
      fechaIngreso: this.parseDate(data.fechaIngreso, 'fechaIngreso'),
      estado,
      lastEventId: eventId,
    });
  }

  async update(
    employeeId: string,
    data: {
      nombre?: string;
      apellido?: string;
      email?: string;
      cargo?: string;
      departamentoId?: string;
      estado?: string;
      fechaRetiro?: string | null;
      lastEventId?: string;
    },
  ) {
    const suppliedFields = Object.entries(data).filter(
      ([key, value]) => key !== 'lastEventId' && value !== undefined,
    );
    if (suppliedFields.length === 0) {
      throw new ProfileError(400, 'Debe indicar al menos un campo para actualizar');
    }
    for (const [field, value] of suppliedFields) {
      if (typeof value === 'string' && !value.trim()) {
        throw new ProfileError(400, `${field} no puede estar vacío`);
      }
    }
    if (data.email !== undefined) this.validateEmail(data.email);
    const current = await this.repository.findByEmployeeId(employeeId);
    if (!current) throw new ProfileError(404, `Perfil con employeeId '${employeeId}' no encontrado`);

    const eventId = data.lastEventId ?? crypto.randomUUID();
    const duplicate = await this.repository.findByLastEventId(eventId);
    if (duplicate) return duplicate;

    const nextEstado = data.estado === undefined ? current.estado : this.parseEstado(data.estado);
    if (current.estado === 'RETIRADO' && nextEstado === 'ACTIVO') {
      throw new ProfileError(409, 'Un perfil RETIRADO no puede volver a ACTIVO');
    }

    const transitionToRetired = current.estado !== 'RETIRADO' && nextEstado === 'RETIRADO';
    const fechaRetiro = nextEstado === 'RETIRADO'
      ? this.parseDate(data.fechaRetiro ?? new Date().toISOString(), 'fechaRetiro')
      : data.fechaRetiro === null ? null : undefined;

    const updated = await this.repository.update(employeeId, {
      ...data,
      estado: nextEstado,
      fechaRetiro,
      lastEventId: eventId,
    });

    if (transitionToRetired) {
      try {
        await this.eventPublisher.publishRetired({
          ...updated,
          fechaRetiro: updated.fechaRetiro ?? new Date(),
        });
      } catch (error) {
        console.error('[PROFILE] No se pudo publicar empleado.retirado:', error);
        throw new ProfileError(503, 'No se pudo publicar el evento de retiro');
      }
    }
    return updated;
  }

  async retire(employeeId: string, lastEventId?: string) {
    return this.update(employeeId, {
      estado: 'RETIRADO',
      lastEventId,
    });
  }

  // ── Procesamiento de evento (Command side via RabbitMQ) ───────────────────

  async processEvent(envelope: EventEnvelope): Promise<ProcessingResult> {
    const { id: eventId, type: eventType, data } = envelope;
    const employeeId = data?.id ?? 'UNKNOWN';

    console.log(
      `[PROFILE] Mensaje recibido | UUID: ${eventId} | Evento: ${eventType} | Empleado: ${employeeId}`
    );

    // Filtrar eventos no gestionados por este servicio
    if (!ALLOWED_EVENTS.includes(eventType as any)) {
      console.log(
        `[PROFILE] Evento ignorado | UUID: ${eventId} | Tipo '${eventType}' no es de interés para este servicio`
      );
      return 'IGNORED';
    }

    if (
      typeof eventId !== 'string'
      || !eventId.trim()
      || typeof data?.id !== 'string'
      || !data.id.trim()
    ) {
      throw new ProfileError(400, 'El evento debe incluir id y data.id');
    }

    // ── Idempotencia: comprobar si el eventId ya fue aplicado ────────────────
    const existing = await this.repository.findByLastEventId(eventId);
    if (existing) {
      console.warn(
        `[PROFILE] [DUPLICADO] Evento ya procesado anteriormente | UUID: ${eventId} | Tipo: ${eventType} | Empleado: ${employeeId}`
      );
      return 'DUPLICATE';
    }

    // ── Despachar según tipo de evento ────────────────────────────────────────
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
      // Conflicto de UNIQUE constraint (condición de carrera entre réplicas)
      if (error?.code === 'P2002') {
        console.warn(
          `[PROFILE] [DUPLICADO] Conflicto UNIQUE detectado al procesar | UUID: ${eventId} | Empleado: ${employeeId}`
        );
        return 'DUPLICATE';
      }
      console.error(
        `[PROFILE] [ERROR] Excepción al procesar evento | UUID: ${eventId} | Tipo: ${eventType} | Empleado: ${employeeId} |`,
        error
      );
      throw error;
    }
  }

  // ── Handlers de eventos ───────────────────────────────────────────────────

  private async handleEmpleadoCreado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data']
  ): Promise<ProcessingResult> {
    this.validateRequiredFields(data, [
      'nombre',
      'apellido',
      'email',
      'cargo',
      'departamentoId',
    ]);
    this.validateEmail(data.email as string);

    await this.repository.create({
      employeeId,
      nombre: data.nombre ?? '',
      apellido: data.apellido ?? '',
      email: data.email ?? '',
      cargo: data.cargo ?? '',
      departamentoId: data.departamentoId ?? '',
      fechaIngreso: data.fechaIngreso
        ? this.parseDate(data.fechaIngreso, 'fechaIngreso')
        : new Date(),
      estado: 'ACTIVO',
      lastEventId: eventId,
    });

    console.log(
      `[PROFILE] [CREADO] Perfil creado | UUID: ${eventId} | Empleado: ${employeeId} | ` +
        `Nombre: ${data.nombre} ${data.apellido} | Email: ${data.email}`
    );
    return 'CREATED';
  }

  private async handleEmpleadoActualizado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data']
  ): Promise<ProcessingResult> {
    if (data.email !== undefined) this.validateEmail(data.email);

    // Verificar que el perfil base ya existe (puede llegar antes del creado en reentregas)
    const profile = await this.repository.findByEmployeeId(employeeId);
    if (!profile) {
      console.warn(
        `[PROFILE] [ACTUALIZADO] Perfil no encontrado para actualizar | UUID: ${eventId} | Empleado: ${employeeId}. ` +
          `Creando perfil desde evento actualizado.`
      );
      // Creación defensiva si el perfil no existe aún (reordenamiento de mensajes)
      this.validateRequiredFields(data, [
        'nombre',
        'apellido',
        'email',
        'cargo',
        'departamentoId',
      ]);
      await this.repository.create({
        employeeId,
        nombre: data.nombre ?? '',
        apellido: data.apellido ?? '',
        email: data.email ?? '',
        cargo: data.cargo ?? '',
        departamentoId: data.departamentoId ?? '',
        fechaIngreso: data.fechaIngreso
          ? this.parseDate(data.fechaIngreso, 'fechaIngreso')
          : new Date(),
        estado: 'ACTIVO',
        lastEventId: eventId,
      });
      return 'CREATED';
    }

    await this.repository.update(employeeId, {
      nombre: data.nombre,
      apellido: data.apellido,
      email: data.email,
      cargo: data.cargo,
      departamentoId: data.departamentoId,
      lastEventId: eventId,
    });

    console.log(
      `[PROFILE] [ACTUALIZADO] Perfil actualizado | UUID: ${eventId} | Empleado: ${employeeId} | ` +
        `Nombre: ${data.nombre} ${data.apellido}`
    );
    return 'UPDATED';
  }

  private async handleEmpleadoRetirado(
    eventId: string,
    employeeId: string,
    data: EventEnvelope['data']
  ): Promise<ProcessingResult> {
    const profile = await this.repository.findByEmployeeId(employeeId);
    if (!profile) {
      console.warn(
        `[PROFILE] [RETIRADO] Perfil no encontrado para retirar | UUID: ${eventId} | Empleado: ${employeeId}. Ignorando.`
      );
      // Retornamos IGNORED de forma segura: no hay perfil que retirar
      return 'IGNORED';
    }

    const fechaRetiro = data.fechaRetiro
      ? this.parseDate(data.fechaRetiro, 'fechaRetiro')
      : new Date();

    await this.repository.retire(employeeId, {
      fechaRetiro,
      lastEventId: eventId,
    });

    console.log(
      `[PROFILE] [RETIRADO] Perfil marcado como RETIRADO | UUID: ${eventId} | Empleado: ${employeeId} | ` +
        `FechaRetiro: ${fechaRetiro.toISOString()}`
    );
    return 'RETIRED';
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private parseEstado(estado?: string): EstadoFiltro | undefined {
    if (estado === undefined || estado.trim() === '') return undefined;
    if (estado === 'ACTIVO' || estado === 'RETIRADO') return estado;
    throw new ProfileError(400, `Estado inválido: ${estado}. Use ACTIVO o RETIRADO`);
  }

  private parseDate(value: string, field: string): Date {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      throw new ProfileError(400, `${field} debe ser una fecha válida`);
    }
    return date;
  }

  private validateRequiredFields(
    data: Record<string, unknown>,
    fields: string[],
  ): void {
    for (const field of fields) {
      if (typeof data[field] !== 'string' || !data[field].trim()) {
        throw new ProfileError(400, `${field} es obligatorio`);
      }
    }
  }

  private validateEmail(email: string): void {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      throw new ProfileError(400, 'email no tiene un formato válido');
    }
  }
}
