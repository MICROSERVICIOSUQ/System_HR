import { VacationRepository, EstadoFiltro } from '../repositories/vacation.repository.ts';

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

export class VacationService {
  constructor(private readonly repository = new VacationRepository()) {}

  // ── Consultas (Read Model) ──────────────────────────────────────────────────

  async getAll(estado?: string) {
    const estadoFiltro = this.parseEstado(estado);
    return this.repository.findAll(estadoFiltro);
  }

  async getByEmployeeId(employeeId: string) {
    return this.repository.findByEmployeeId(employeeId);
  }

  // ── Procesamiento de eventos RabbitMQ ───────────────────────────────────────

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

  private parseEstado(estado?: string): EstadoFiltro | undefined {
    if (estado === 'ACTIVO' || estado === 'RETIRADO') return estado;
    return undefined;
  }
}
