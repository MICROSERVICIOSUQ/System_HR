import amqplib, { Channel, ChannelModel } from 'amqplib';
import { randomUUID } from 'node:crypto';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { cabecerasConContexto } from '../observability/amqp-tracing.ts';

export interface RetiredEmployeeEvent {
  id: string;
  type: 'empleado.retirado';
  version: '1.0';
  occurredAt: string;
  producer: 'profiles-service';
  data: {
    id: string;
    nombre: string;
    apellido: string;
    email: string;
    departamentoId: string;
    cargo: string;
    fechaIngreso: string;
    fechaRetiro: string;
  };
}

export class EmployeeEventPublisher {
  async publishRetired(profile: {
    empleadoId: string;
    nombre: string;
    apellido: string;
    email: string;
    departamentoId: string;
    cargo: string;
    fechaIngreso: Date;
    fechaRetiro: Date;
  }): Promise<string> {
    const event: RetiredEmployeeEvent = {
      id: randomUUID(),
      type: 'empleado.retirado',
      version: '1.0',
      occurredAt: new Date().toISOString(),
      // El productor es este servicio, no employees-service.
      //
      // Decía 'employees-service', lo que contradecía el tipo declarado arriba
      // (`producer: 'profiles-service'`). El error nunca salió porque el
      // servicio se ejecuta con tsx, que elimina los tipos sin comprobarlos; se
      // destapó al correr `tsc --noEmit`.
      //
      // No es solo un error de tipos: el campo `producer` del envelope existe
      // para saber quién emitió un evento cuando hay que depurar la cadena. Un
      // servicio que firma con el nombre de otro hace que esa información
      // engañe, que es peor que no tenerla.
      producer: 'profiles-service',
      data: {
        id: profile.empleadoId,
        nombre: profile.nombre,
        apellido: profile.apellido,
        email: profile.email,
        departamentoId: profile.departamentoId,
        cargo: profile.cargo,
        fechaIngreso: profile.fechaIngreso.toISOString().slice(0, 10),
        fechaRetiro: profile.fechaRetiro.toISOString(),
      },
    };

    let connection: ChannelModel | undefined;
    let channel: Channel | undefined;
    try {
      connection = await amqplib.connect(rabbitConfig.url);
      channel = await connection.createChannel();
      await channel.publish(
        rabbitConfig.exchangeName,
        '',
        Buffer.from(JSON.stringify(event)),
        {
          contentType: 'application/json',
          persistent: true,
          // Contexto de traza en las cabeceras (Reto 8): sin esto el consumidor
          // de este evento abriría una traza nueva en lugar de continuar la que
          // viene de la petición HTTP que provocó el retiro.
          headers: cabecerasConContexto(),
        },
      );
      return event.id;
    } finally {
      await channel?.close().catch(() => undefined);
      await connection?.close().catch(() => undefined);
    }
  }
}
