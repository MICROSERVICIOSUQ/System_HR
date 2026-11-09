import { cabecerasConContexto } from '../observability/amqp-tracing.ts';
import { log } from '../observability/logger.ts';
import amqplib, { Channel, ChannelModel } from 'amqplib';
import { rabbitConfig } from '../config/rabbitmq.ts';

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
  private static connection: ChannelModel | null = null;
  private static channel: Channel | null = null;
  private static connecting: Promise<void> | null = null;
  private readonly exchangeName = 'hr.events';

  private async ensureConnected(): Promise<void> {
    if (RabbitMQPublisher.channel) return;
    if (!RabbitMQPublisher.connecting) {
      RabbitMQPublisher.connecting = this.connect().finally(() => { RabbitMQPublisher.connecting = null; });
    }
    await RabbitMQPublisher.connecting;
  }

  private async connect(): Promise<void> {

    try {
      RabbitMQPublisher.connection = await amqplib.connect(rabbitConfig.url);
      RabbitMQPublisher.channel = await RabbitMQPublisher.connection.createChannel();

      // Verificar que el exchange existe (no lo creamos, ya existe)
      await RabbitMQPublisher.channel.checkExchange(this.exchangeName);

      RabbitMQPublisher.connection.on('error', (err) => {
        log.error('[RABBITMQ-PUBLISHER] Error en conexión:', err.message);
        RabbitMQPublisher.connection = null;
        RabbitMQPublisher.channel = null;
      });

      RabbitMQPublisher.connection.on('close', () => {
        log.warn('[RABBITMQ-PUBLISHER] Conexión cerrada.');
        RabbitMQPublisher.connection = null;
        RabbitMQPublisher.channel = null;
      });

      log.info('[RABBITMQ-PUBLISHER] Conectado al exchange hr.events');
    } catch (error: any) {
      RabbitMQPublisher.connection = null;
      RabbitMQPublisher.channel = null;
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

    if (!RabbitMQPublisher.channel) {
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

    // Publicar al exchange fanout hr.events (routing key vacía para fanout)
    RabbitMQPublisher.channel.publish(this.exchangeName, '', payload, {
      contentType: 'application/json',
      persistent: true,
      headers: cabecerasConContexto(),
    });

    log.info(
      `[VACATIONS] [PUBLICADO] ${type} | VacationPeriod: ${data.id} | Empleado: ${data.empleadoId}`
    );
  }

  async close(): Promise<void> {
    try {
      if (RabbitMQPublisher.channel) await RabbitMQPublisher.channel.close();
      if (RabbitMQPublisher.connection) await RabbitMQPublisher.connection.close();
      RabbitMQPublisher.channel = null;
      RabbitMQPublisher.connection = null;
    } catch (err: any) {
      log.error('[RABBITMQ-PUBLISHER] Error cerrando:', err.message);
    }
  }
}
