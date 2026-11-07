import amqplib, { Channel, ChannelModel } from 'amqplib';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { cabecerasConContexto } from '../observability/amqp-tracing.ts';

export interface VacacionesProgramadasEvent {
  id: string;           // UUID del VacationPeriod
  empleadoId: string;
  fechaInicio: string;  // ISO date string
  fechaFin: string;     // ISO date string
  estado: string;
  fechaCreacion: string; // ISO datetime string
}

export interface EventEnvelope<T> {
  id: string;
  type: string;
  version: string;
  occurredAt: string;
  producer: string;
  correlationId?: string;
  data: T;
}

export class RabbitMQPublisher {
  private connection: ChannelModel | null = null;
  private channel: Channel | null = null;
  private readonly exchangeName = 'hr.events';

  private async ensureConnected(): Promise<void> {
    if (this.channel) return;

    try {
      this.connection = await amqplib.connect(rabbitConfig.url);
      this.channel = await this.connection.createChannel();

      // Verificar que el exchange existe (no lo creamos, ya existe)
      await this.channel.checkExchange(this.exchangeName);

      this.connection.on('error', (err) => {
        console.error('[RABBITMQ-PUBLISHER] Error en conexión:', err.message);
        this.connection = null;
        this.channel = null;
      });

      this.connection.on('close', () => {
        console.warn('[RABBITMQ-PUBLISHER] Conexión cerrada.');
        this.connection = null;
        this.channel = null;
      });

      console.log('[RABBITMQ-PUBLISHER] Conectado al exchange hr.events');
    } catch (error: any) {
      this.connection = null;
      this.channel = null;
      throw new Error(`[RABBITMQ-PUBLISHER] No se pudo conectar: ${error.message}`);
    }
  }

  async publishVacacionesProgramadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string
  ): Promise<void> {
    await this.publishEvent('vacaciones.programadas', data, correlationId);
  }

  async publishVacacionesReprogramadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string
  ): Promise<void> {
    await this.publishEvent('vacaciones.reprogramadas', data, correlationId);
  }

  /**
   * Publicado por el scheduler cuando llega la fechaInicio de un período.
   * El auth-service lo consume para suspender temporalmente la cuenta.
   * Reutiliza el mismo payload que vacaciones.programadas: lleva empleadoId,
   * que es el campo con el que el consumidor identifica la cuenta.
   */
  async publishVacacionesIniciadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string
  ): Promise<void> {
    await this.publishEvent('vacaciones.iniciadas', data, correlationId);
  }

  /**
   * Publicado por el scheduler cuando pasa la fechaFin de un período.
   * El auth-service lo consume para reactivar la cuenta, salvo que el empleado
   * haya sido retirado durante el período.
   */
  async publishVacacionesFinalizadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string
  ): Promise<void> {
    await this.publishEvent('vacaciones.finalizadas', data, correlationId);
  }

  private async publishEvent(
    type: string,
    data: VacacionesProgramadasEvent,
    correlationId?: string
  ): Promise<void> {
    await this.ensureConnected();

    if (!this.channel) {
      throw new Error('[RABBITMQ-PUBLISHER] Canal no disponible');
    }

    const envelope: EventEnvelope<VacacionesProgramadasEvent> = {
      id: `vac-${Date.now()}-${Math.floor(Math.random() * 10000)}`,
      type,
      version: '1.0',
      occurredAt: new Date().toISOString(),
      producer: 'vacations-service',
      ...(correlationId && { correlationId }),
      data: {
        id: data.id,
        empleadoId: data.empleadoId,
        fechaInicio: data.fechaInicio,
        fechaFin: data.fechaFin,
        estado: data.estado,
        fechaCreacion: data.fechaCreacion,
      },
    };

    const payload = Buffer.from(JSON.stringify(envelope));

    // Publicar al exchange fanout hr.events (routing key vacía para fanout).
    // Las cabeceras llevan el contexto de traza (traceparent, W3C): es lo que
    // permite que el span del consumidor cuelgue de esta misma traza en lugar de
    // aparecer como una traza suelta en Zipkin. Por HTTP esto lo hace el SDK
    // solo; por el broker hay que inyectarlo a mano.
    this.channel.publish(this.exchangeName, '', payload, {
      contentType: 'application/json',
      persistent: true,
      headers: cabecerasConContexto(),
    });

    console.log(
      `[VACATIONS] [PUBLICADO] ${type} | VacationPeriod: ${data.id} | Empleado: ${data.empleadoId}`
    );
  }

  async close(): Promise<void> {
    try {
      if (this.channel) await this.channel.close();
      if (this.connection) await this.connection.close();
    } catch (err: any) {
      console.error('[RABBITMQ-PUBLISHER] Error cerrando:', err.message);
    }
  }
}
