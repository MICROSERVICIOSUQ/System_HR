import amqplib, { Channel, ChannelModel } from 'amqplib';
import { randomUUID } from 'node:crypto';
import { rabbitConfig } from '../config/rabbitmq.ts';

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
    employeeId: string;
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
      producer: 'employees-service',
      data: {
        id: profile.employeeId,
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
        { contentType: 'application/json', persistent: true },
      );
      return event.id;
    } finally {
      await channel?.close().catch(() => undefined);
      await connection?.close().catch(() => undefined);
    }
  }
}
