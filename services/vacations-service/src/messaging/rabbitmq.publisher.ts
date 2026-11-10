import { cabecerasConContexto } from '../observability/amqp-tracing.ts';
import { log } from '../observability/logger.ts';
import amqplib, { ConfirmChannel, ChannelModel } from 'amqplib';
import { randomUUID } from 'node:crypto';
import type { Prisma } from '../generated/prisma/client.ts';
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
  private static channel: ConfirmChannel | null = null;
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
      RabbitMQPublisher.channel = await RabbitMQPublisher.connection.createConfirmChannel();

      const channel = RabbitMQPublisher.channel;
      channel.on('error', () => { if (RabbitMQPublisher.channel === channel) RabbitMQPublisher.channel = null; });
      channel.on('close', () => { if (RabbitMQPublisher.channel === channel) RabbitMQPublisher.channel = null; });

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
    correlationId?: string,
    transaction?: Prisma.TransactionClient
  ): Promise<void> {
    await this.publishEvent('vacaciones.programadas', data, correlationId, transaction);
  }

  async publishVacacionesReprogramadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string,
    transaction?: Prisma.TransactionClient
  ): Promise<void> {
    await this.publishEvent('vacaciones.reprogramadas', data, correlationId, transaction);
  }

  /**
   * Publicado por el scheduler cuando llega la fechaInicio de un período.
   * El auth-service lo consume para suspender temporalmente la cuenta.
   * Reutiliza el mismo payload que vacaciones.programadas: lleva empleadoId,
   * que es el campo con el que el consumidor identifica la cuenta.
   */
  async publishVacacionesIniciadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string,
    transaction?: Prisma.TransactionClient
  ): Promise<void> {
    await this.publishEvent('vacaciones.iniciadas', data, correlationId, transaction);
  }

  /**
   * Publicado por el scheduler cuando pasa la fechaFin de un período.
   * El auth-service lo consume para reactivar la cuenta, salvo que el empleado
   * haya sido retirado durante el período.
   */
  async publishVacacionesFinalizadas(
    data: VacacionesProgramadasEvent,
    correlationId?: string,
    transaction?: Prisma.TransactionClient
  ): Promise<void> {
    await this.publishEvent('vacaciones.finalizadas', data, correlationId, transaction);
  }

  private async publishEvent(
    type: string,
    data: VacacionesProgramadasEvent,
    correlationId?: string,
    transaction?: Prisma.TransactionClient
  ): Promise<void> {
    if (!transaction) throw new Error('El evento requiere la transacción del período');
    const envelope: EventEnvelope<VacacionesProgramadasEvent> = {
      id: randomUUID(),
      type,
      version: '1.0',
      occurredAt: new Date().toISOString(),
      producer: 'vacations-service',
      ...(correlationId && { correlationId }),
      data,
    };
    await transaction.outboxEvento.create({ data: {
      id: envelope.id,
      tipo: type,
      agregadoId: data.empleadoId,
      payload: envelope as unknown as Prisma.InputJsonValue,
      headers: cabecerasConContexto() as Prisma.InputJsonValue,
    } });
  }

  /** Solo el worker llama al broker. Se espera su confirmación, no solo el buffer local. */
  async enviarAlBroker(envelope: EventEnvelope<VacacionesProgramadasEvent>, headers: Record<string, unknown>): Promise<void> {
    await this.ensureConnected();
    const channel = RabbitMQPublisher.channel!;
    await new Promise<void>((resolve, reject) => {
      const returned = (message: amqplib.Message) => {
        if (message.properties.messageId === envelope.id) done(new Error('Evento sin cola de destino'));
      };
      const done = (error?: Error | null) => {
        clearTimeout(timer);
        channel.off('return', returned);
        if (error) reject(error); else resolve();
      };
      const timer = setTimeout(() => done(new Error('RabbitMQ no confirmó el evento')), 5000);
      channel.on('return', returned);
      try {
        channel.publish(this.exchangeName, '', Buffer.from(JSON.stringify(envelope)), {
          contentType: 'application/json', persistent: true, mandatory: true,
          messageId: envelope.id, headers,
        }, done);
      } catch (error) {
        done(error as Error);
      }
    });
    log.info(`[VACATIONS] [PUBLICADO] ${envelope.type} | eventId: ${envelope.id}`);
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
