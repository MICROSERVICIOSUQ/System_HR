import amqplib, {
  type ChannelModel,
  type ConfirmChannel,
  type ConsumeMessage,
} from 'amqplib';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { logger } from '../observability/logger.ts';
import { ProfileService } from '../services/profile.service.ts';
import { conSpanDeConsumo } from '../observability/amqp-tracing.ts';

export class RabbitMQConsumer {
  private connection: ChannelModel | null = null;
  private channel: ConfirmChannel | null = null;
  private isConnecting = false;

  constructor(private readonly service = new ProfileService()) {}

  async start(): Promise<void> {
    if (this.isConnecting) return;
    this.isConnecting = true;

    const connectWithRetry = async () => {
      try {
        logger.info('[RABBITMQ] Conectando', {
          host: rabbitConfig.host,
          puerto: rabbitConfig.port,
        });
        this.connection = await amqplib.connect(rabbitConfig.url);
        this.channel = await this.connection.createConfirmChannel();

        // La cola primaria conserva su declaración canónica del definitions.json.
        // La cola de reintento devuelve mensajes tras el TTL; después del máximo
        // de intentos se conservan en una DLQ durable para inspección.
        await this.channel.assertQueue(rabbitConfig.retryQueueName, {
          durable: true,
          arguments: {
            'x-message-ttl': rabbitConfig.retryDelayMilliseconds,
            'x-dead-letter-exchange': '',
            'x-dead-letter-routing-key': rabbitConfig.queueName,
          },
        });
        await this.channel.assertQueue(rabbitConfig.deadLetterQueueName, {
          durable: true,
        });

        // Configurar prefetch para consumo balanceado
        await this.channel.prefetch(10);

        this.connection.on('error', (err) => {
          logger.error('[RABBITMQ] Error en la conexión', { error: err.message });
        });

        this.connection.on('close', () => {
          logger.warn('[RABBITMQ] Conexión cerrada, reconectando', { enSegundos: 5 });
          this.connection = null;
          this.channel = null;
          this.isConnecting = false;
          setTimeout(() => this.start(), 5000);
        });

        logger.info('[RABBITMQ] Conectado, escuchando la cola', {
          cola: rabbitConfig.queueName,
        });

        // Consumir la cola primaria preexistente.
        await this.channel.consume(
          rabbitConfig.queueName,
          async (msg: ConsumeMessage | null) => {
            if (!msg) return;
            await this.handleMessage(msg);
          },
          { noAck: false }
        );

        this.isConnecting = false;
      } catch (error: any) {
        logger.error('[RABBITMQ] Fallo de conexión, se reintentará', {
          error: error.message,
          enSegundos: 5,
        });
        this.isConnecting = false;
        setTimeout(connectWithRetry, 5000);
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
        logger.error('[PROFILE] [ERROR] El mensaje no es un JSON válido, se descarta', {
          contenido: contentStr.slice(0, 500),
        });
        this.channel.ack(msg);
        return;
      }

      // Igual que en los demas consumidores: se extrae el contexto de las
      // cabeceras para que el span quede en el mismo arbol que el publicador.
      await conSpanDeConsumo(
        envelope?.type ?? 'desconocido',
        msg.properties?.headers,
        {
          'event.id': String(envelope?.id ?? ''),
          'messaging.destination.name': rabbitConfig.queueName,
        },
        () => this.service.processEvent(envelope),
      );

      // ACK siempre: procesado, duplicado o ignorado
      this.channel.ack(msg);
    } catch (error: any) {
      logger.error('[PROFILE] [ERROR] Excepción no controlada procesando el mensaje', {
        error: error.message,
      });
      await this.reintentarOEnviarALaDlq(msg, error);
    }
  }

  private async reintentarOEnviarALaDlq(
    msg: ConsumeMessage,
    error: unknown,
  ): Promise<void> {
    const channel = this.channel;
    if (!channel) return;

    const cabecera = msg.properties.headers?.['x-profile-retry-attempt'];
    const intentosPrevios = Number(cabecera ?? 0);
    const intentos = Number.isInteger(intentosPrevios) && intentosPrevios >= 0
      ? intentosPrevios
      : 0;
    const agotados = intentos >= rabbitConfig.maxRetries;
    const destino = agotados
      ? rabbitConfig.deadLetterQueueName
      : rabbitConfig.retryQueueName;
    const siguienteIntento = agotados ? intentos : intentos + 1;

    try {
      channel.sendToQueue(destino, msg.content, {
        persistent: true,
        contentType: msg.properties.contentType,
        contentEncoding: msg.properties.contentEncoding,
        headers: {
          ...msg.properties.headers,
          'x-profile-retry-attempt': siguienteIntento,
          ...(agotados && {
            'x-profile-failure-type': error instanceof Error ? error.name : 'ProcessingError',
          }),
        },
      });
      await channel.waitForConfirms();
      channel.ack(msg);
      logger.warn(
        agotados
          ? '[PROFILE] Mensaje enviado a la DLQ tras agotar reintentos'
          : '[PROFILE] Mensaje enviado a reintento',
        {
          destino,
          intentos: siguienteIntento,
          eventId: msg.properties.messageId,
        },
      );
    } catch (publishError) {
      logger.error('[PROFILE] No se pudo enviar el mensaje a reintento/DLQ', {
        destino,
        error: publishError instanceof Error ? publishError.message : String(publishError),
      });
      try {
        channel.nack(msg, false, true);
      } catch (nackError) {
        logger.error('[PROFILE] No se pudo reencolar el mensaje sin confirmar', {
          error: nackError instanceof Error ? nackError.message : String(nackError),
        });
      }
    }
  }

  async stop(): Promise<void> {
    try {
      if (this.channel) await this.channel.close();
      if (this.connection) await this.connection.close();
      logger.info('[RABBITMQ] Conexión cerrada ordenadamente');
    } catch (err: any) {
      logger.error('[RABBITMQ] Error cerrando la conexión', { error: err.message });
    }
  }
}
