import { conSpanDeConsumo } from '../observability/amqp-tracing.ts';
import { brokerHealth } from '../observability/health.ts';
import { log } from '../observability/logger.ts';
import amqplib, { Channel, ChannelModel, ConsumeMessage } from 'amqplib';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { VacationService } from '../services/vacation.service.ts';

export class RabbitMQConsumer {
  private connection: ChannelModel | null = null;
  private channel: Channel | null = null;
  private isConnecting = false;
  private stopped = false;

  constructor(private readonly service = new VacationService()) {}

  async start(): Promise<void> {
    if (this.isConnecting || this.stopped) return;
    this.isConnecting = true;

    const connectWithRetry = async () => {
      if (this.stopped) return;
      try {
        log.info(`[RABBITMQ] Conectando a ${rabbitConfig.host}:${rabbitConfig.port}...`);
        this.connection = await amqplib.connect(rabbitConfig.url);
        this.channel = await this.connection.createChannel();

        this.channel.on('error', () => {
          brokerHealth.connected = false;
          void this.connection?.close().catch(() => {});
        });
        this.channel.on('close', () => { brokerHealth.connected = false; });
        await this.channel.prefetch(10);

        this.connection.on('error', (err) => {
          log.error('[RABBITMQ] Error en conexión:', err.message);
        });

        this.connection.on('close', () => {
          log.warn('[RABBITMQ] Conexión cerrada. Reconectando en 5 segundos...');
          brokerHealth.connected = false;
          this.connection = null;
          this.channel = null;
          this.isConnecting = false;
          if (!this.stopped) setTimeout(() => this.start(), 5000);
        });

        log.info(
          `[RABBITMQ] Conectado exitosamente. Escuchando cola: '${rabbitConfig.queueName}'`
        );

        // Consumir únicamente la cola vacations.queue sin modificar topología
        await this.channel.consume(
          rabbitConfig.queueName,
          async (msg: ConsumeMessage | null) => {
            if (!msg) return;
            await this.handleMessage(msg);
          },
          { noAck: false }
        );

        brokerHealth.connected = true;
        this.isConnecting = false;
      } catch (error: any) {
        log.error(
          `[RABBITMQ] Fallo de conexión: ${error.message}. Reintentando en 5 segundos...`
        );
        this.isConnecting = false;
        if (!this.stopped) setTimeout(connectWithRetry, 5000);
      }
    };

    await connectWithRetry();
  }

  private async handleMessage(msg: ConsumeMessage): Promise<void> {
    if (!this.channel) return;

    try {
      const contentStr = msg.content.toString();
      let envelope: any;

      try {
        envelope = JSON.parse(contentStr);
      } catch (parseErr) {
        log.error(
          '[VACATIONS] [ERROR] Mensaje recibido no es un JSON válido; se rechaza sin imprimir su contenido'
        );
        this.channel.ack(msg);
        return;
      }

      const result = await conSpanDeConsumo(envelope.type, msg.properties.headers,
        { 'event.id': String(envelope.id), 'messaging.destination.name': rabbitConfig.queueName },
        () => this.service.processEvent(envelope));

      if (result === 'DUPLICATE') {
        log.info(`Evento duplicado omitido: ${envelope.id}`);
      }
      this.channel.ack(msg);
    } catch (error: any) {
      log.error(
        '[VACATIONS] [ERROR] Excepción no controlada procesando mensaje:',
        error.message
      );
      this.channel.nack(msg, false, true);
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    brokerHealth.connected = false;
    try {
      if (this.channel) await this.channel.close();
      if (this.connection) await this.connection.close();
      log.info('[RABBITMQ] Conexión cerrada ordenadamente.');
    } catch (err: any) {
      log.error('[RABBITMQ] Error cerrando conexión:', err.message);
    }
  }
}
