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

  async publishVacacionesProgramadas(data: VacacionesProgramadasEvent): Promise<void> {
    await this.ensureConnected();

    if (!this.channel) {
      throw new Error('[RABBITMQ-PUBLISHER] Canal no disponible');
    }

    const envelope = {
      id: `vac-${Date.now()}-${Math.floor(Math.random() * 10000)}`,
      type: 'vacaciones.programadas',
      version: '1.0',
      occurredAt: new Date().toISOString(),
      producer: 'vacations-service',
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
    this.channel.publish(this.exchangeName, '', payload, {
      contentType: 'application/json',
      persistent: true,
    });

    console.log(
      `[VACATIONS] [PUBLICADO] vacaciones.programadas | VacationPeriod: ${data.id} | Empleado: ${data.empleadoId}`
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
