import amqplib, { Channel, ChannelModel, ConsumeMessage } from 'amqplib';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { ProfileService } from '../services/profile.service.ts';

export class RabbitMQConsumer {
  private connection: ChannelModel | null = null;
  private channel: Channel | null = null;
  private isConnecting = false;

  constructor(private readonly service = new ProfileService()) {}

  async start(): Promise<void> {
    if (this.isConnecting) return;
    this.isConnecting = true;

    const connectWithRetry = async () => {
      try {
        console.log(`[RABBITMQ] Conectando a ${rabbitConfig.host}:${rabbitConfig.port}...`);
        this.connection = await amqplib.connect(rabbitConfig.url);
        this.channel = await this.connection.createChannel();

        // Configurar prefetch para consumo balanceado
        await this.channel.prefetch(10);

        this.connection.on('error', (err) => {
          console.error('[RABBITMQ] Error en conexión:', err.message);
        });

        this.connection.on('close', () => {
          console.warn('[RABBITMQ] Conexión cerrada. Reconectando en 5 segundos...');
          this.connection = null;
          this.channel = null;
          this.isConnecting = false;
          setTimeout(() => this.start(), 5000);
        });

        console.log(
          `[RABBITMQ] Conectado exitosamente. Escuchando cola: '${rabbitConfig.queueName}'`
        );

        // Consumir únicamente la cola preexistente sin modificar topología
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
        console.error(
          `[RABBITMQ] Fallo de conexión: ${error.message}. Reintentando en 5 segundos...`
        );
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
        console.error(
          '[PROFILE] [ERROR] Mensaje no contiene un JSON válido. Descartando:',
          contentStr
        );
        this.channel.ack(msg);
        return;
      }

      await this.service.processEvent(envelope);

      // ACK siempre: procesado, duplicado o ignorado
      this.channel.ack(msg);
    } catch (error: any) {
      console.error(
        '[PROFILE] [ERROR] Excepción no controlada procesando mensaje:',
        error.message
      );
      // ACK para evitar bloqueo infinito de la cola
      this.channel.ack(msg);
    }
  }

  async stop(): Promise<void> {
    try {
      if (this.channel) await this.channel.close();
      if (this.connection) await this.connection.close();
      console.log('[RABBITMQ] Conexión cerrada ordenadamente.');
    } catch (err: any) {
      console.error('[RABBITMQ] Error cerrando conexión:', err.message);
    }
  }
}
