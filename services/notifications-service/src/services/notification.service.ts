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
    // Campos de los eventos del auth-service (Reto 5)
    token?: string;
    motivo?: string;
    permanente?: boolean;
    [key: string]: unknown;
  };
}

export type ProcessingResult = 'PROCESSED' | 'DUPLICATE' | 'IGNORED' | 'ERROR';

/**
 * Eventos que este servicio notifica.
 *
 * Los cuatro últimos los emite el auth-service (Reto 5) y cierran el ciclo de
 * vida de la cuenta. Importante: el correo de bienvenida del onboarding se
 * dispara con `usuario.creado`, no con `empleado.creado`, porque es
 * `usuario.creado` el que trae el token de activación; sin token el correo sería
 * inútil.
 */
const ALLOWED_EVENTS: readonly string[] = [
  'empleado.creado',
  'empleado.actualizado',
  'empleado.retirado',
  'usuario.creado',
  'usuario.recuperacion',
  'cuenta.activada',
  'cuenta.desactivada',
];

/** Clasificación de la notificación simulada, según la familia del evento. */
type TipoNotificacion = 'SEGURIDAD' | 'CUENTA' | 'EMPLEADO';

/**
 * Compone el texto del correo que se simula para cada evento.
 *
 * En producción estos logs serían el envío real de un correo; para los eventos de
 * seguridad, con un enlace del estilo
 * `https://app.empresa.com/reset?token=<token>`.
 */
function componerNotificacion(
  eventType: string,
  data: EventEnvelope['data']
): { tipo: TipoNotificacion; mensaje: string } | null {
  const token = typeof data?.token === 'string' ? data.token : '';
  const motivo = typeof data?.motivo === 'string' ? data.motivo : '';
  const permanente = data?.permanente === true;

  switch (eventType) {
    case 'usuario.creado':
      return {
        tipo: 'SEGURIDAD',
        mensaje:
          'Para establecer su contraseña use el siguiente enlace: '
          + `https://app.empresa.com/reset?token=${token}`,
      };

    case 'usuario.recuperacion':
      return {
        tipo: 'SEGURIDAD',
        mensaje:
          'Para restablecer su contraseña use el siguiente enlace: '
          + `https://app.empresa.com/reset?token=${token}`,
      };

    case 'cuenta.desactivada':
      return {
        tipo: 'CUENTA',
        mensaje: permanente
          ? `Su cuenta fue desactivada de forma permanente. Motivo: ${motivo}.`
          : `Su cuenta fue desactivada temporalmente. Motivo: ${motivo}.`,
      };

    case 'cuenta.activada':
      return {
        tipo: 'CUENTA',
        mensaje: motivo === 'FIN_VACACIONES'
          ? 'Bienvenido de regreso. Su cuenta fue reactivada al finalizar sus vacaciones.'
          : `Su cuenta fue activada. Motivo: ${motivo}.`,
      };

    default:
      // Los eventos de empleado ya se registraban antes del Reto 5 sin
      // simular correo; se mantienen tal cual para no alterar ese contrato.
      return null;
  }
}

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
    if (!ALLOWED_EVENTS.includes(eventType)) {
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

      // Simulación del envío del correo, en el formato que pide el enunciado.
      const notificacion = componerNotificacion(eventType, data);
      if (notificacion) {
        console.log(
          `[NOTIFICACIÓN] Tipo: ${notificacion.tipo} | Para: ${email} | Mensaje: "${notificacion.mensaje}"`
        );
      }

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
