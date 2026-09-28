import { ProfileRepository, EstadoFiltro } from '../repositories/profile.repository.ts';

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
    [key: string]: any;
  };
}

export type ProcessingResult = 'CREATED' | 'UPDATED' | 'RETIRED' | 'DUPLICATE' | 'IGNORED' | 'ERROR';

// ─── Tipos de eventos soportados ─────────────────────────────────────────────

const ALLOWED_EVENTS = ['empleado.creado', 'empleado.actualizado', 'empleado.retirado'] as const;

// ─── Servicio principal ──────────────────────────────────────────────────────

export class ProfileService {
  constructor(private readonly repository = new ProfileRepository()) {}

  // ── Queries (Read Model) ──────────────────────────────────────────────────

  async getAll(estado?: string) {
    const estadoFiltro = this.parseEstado(estado);
    return this.repository.findAll(estadoFiltro);
  }

  async getByEmployeeId(employeeId: string) {
    return this.repository.findByEmployeeId(employeeId);
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
    await this.repository.create({
      employeeId,
      nombre: data.nombre ?? '',
      apellido: data.apellido ?? '',
      email: data.email ?? '',
      cargo: data.cargo ?? '',
      departamentoId: data.departamentoId ?? '',
      fechaIngreso: data.fechaIngreso ? new Date(data.fechaIngreso) : new Date(),
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
    // Verificar que el perfil base ya existe (puede llegar antes del creado en reentregas)
    const profile = await this.repository.findByEmployeeId(employeeId);
    if (!profile) {
      console.warn(
        `[PROFILE] [ACTUALIZADO] Perfil no encontrado para actualizar | UUID: ${eventId} | Empleado: ${employeeId}. ` +
          `Creando perfil desde evento actualizado.`
      );
      // Creación defensiva si el perfil no existe aún (reordenamiento de mensajes)
      await this.repository.create({
        employeeId,
        nombre: data.nombre ?? '',
        apellido: data.apellido ?? '',
        email: data.email ?? '',
        cargo: data.cargo ?? '',
        departamentoId: data.departamentoId ?? '',
        fechaIngreso: data.fechaIngreso ? new Date(data.fechaIngreso) : new Date(),
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

    const fechaRetiro = data.fechaRetiro ? new Date(data.fechaRetiro) : new Date();

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
    if (estado === 'ACTIVO' || estado === 'RETIRADO') return estado;
    return undefined;
  }
}
