import { NotificationRepository } from '../repositories/notification.repository.ts';
import { NotificationError } from '../errors/notification.errors.ts';

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
    departamentoId?: string;
    cargo?: string;
    fechaIngreso?: string;
    fechaRetiro?: string | null;
    [key: string]: unknown;
  };
}

export type ProcessingResult = 'PROCESSED' | 'DUPLICATE' | 'IGNORED' | 'ERROR';

export class NotificationService {
  constructor(private readonly repository = new NotificationRepository()) {}

  async getAll(pagination?: {
    page: number;
    limit: number;
    sortBy: 'messageId' | 'eventType' | 'producer' | 'employeeId' | 'employeeName' | 'email' | 'processedAt';
    order: 'asc' | 'desc';
  }) {
    const result = await this.repository.findAll(pagination);
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

  async getByMessageId(messageId: string) {
    if (!messageId.trim()) {
      throw new NotificationError(400, 'messageId es obligatorio');
    }
    return this.repository.findByMessageId(messageId);
  }

  async processEvent(envelope: EventEnvelope): Promise<ProcessingResult> {
    const { id: messageId, type: eventType, producer, data } = envelope;

    console.log(`[NOTIFICATIONS] Mensaje recibido | UUID: ${messageId} | Tipo: ${eventType} | Emisor: ${producer}`);

    // Filtrar eventos permitidos
    const allowedEvents = ['empleado.creado', 'empleado.actualizado', 'empleado.retirado'];
    if (!allowedEvents.includes(eventType)) {
      console.log(`[NOTIFICATIONS] Evento ignorado | UUID: ${messageId} | Tipo '${eventType}' no es de interés para este servicio`);
      return 'IGNORED';
    }

    if (
      typeof messageId !== 'string'
      || !messageId.trim()
      || typeof eventType !== 'string'
      || typeof data?.id !== 'string'
      || !data.id.trim()
    ) {
      throw new NotificationError(400, 'El evento debe incluir id, type y data.id');
    }

    const employeeId = data?.id ?? 'UNKNOWN';
    const employeeName = `${data?.nombre ?? ''} ${data?.apellido ?? ''}`.trim() || 'Desconocido';
    const email = data?.email ?? '';

    // Verificación de Idempotencia por messageId
    const existing = await this.repository.findByMessageId(messageId);
    if (existing) {
      console.warn(`[NOTIFICATIONS] [DUPLICADO] Mensaje ya procesado anteriormente | UUID: ${messageId} | Tipo: ${eventType} | Empleado: ${employeeId} (${employeeName})`);
      return 'DUPLICATE';
    }

    // Persistencia del historial de notificación
    try {
      await this.repository.create({
        messageId,
        eventType,
        producer: producer || 'employees-service',
        employeeId,
        employeeName,
        email,
        payload: envelope,
      });

      console.log(`[NOTIFICATIONS] [ÉXITO] Notificación registrada | UUID: ${messageId} | Tipo: ${eventType} | Empleado: ${employeeId} (${employeeName}) | Email: ${email}`);
      return 'PROCESSED';
    } catch (error: any) {
      // Si ocurre condición de carrera en BD y salta el constraint UNIQUE
      if (error?.code === 'P2002') {
        console.warn(`[NOTIFICATIONS] [DUPLICADO] Conflicto UNIQUE detectado al insertar | UUID: ${messageId}`);
        return 'DUPLICATE';
      }
      console.error(`[NOTIFICATIONS] [ERROR] Error al persistir notificación | UUID: ${messageId} | Error:`, error);
      throw error;
    }
  }
}
