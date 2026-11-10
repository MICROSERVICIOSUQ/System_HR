import { after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';
import prisma from '../config/db.ts';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { VacationService } from '../services/vacation.service.ts';
import { VacationRepository } from '../repositories/vacation.repository.ts';
import { RabbitMQPublisher } from '../messaging/rabbitmq.publisher.ts';
import { OutboxPublisher } from '../messaging/outbox.publisher.ts';

beforeEach(async () => { await prisma.outboxEvento.deleteMany(); });
after(async () => { await prisma.$disconnect(); });

async function fixture() {
  const employeeId = randomUUID();
  await prisma.vacationBalance.create({ data: {
    employeeId, nombre: 'Outbox', apellido: 'Prueba', email: employeeId + '@example.test',
    departamentoId: 'D-OUTBOX', lastEventId: randomUUID(),
  } });
  const day = (offset: number) => {
    const date = new Date(); date.setUTCDate(date.getUTCDate() + offset);
    return date.toISOString().slice(0, 10);
  };
  return { empleadoId: employeeId, fechaInicio: day(5), fechaFin: day(8) };
}

test('crear guarda período y evento juntos sin necesitar conexión al broker', async () => {
  const dto = await fixture();
  const period = await new VacationService().createPeriod(dto, 'correlacion-outbox');
  const event = await prisma.outboxEvento.findFirstOrThrow({ where: { agregadoId: dto.empleadoId } });
  assert.equal((event.payload as any).data.id, period.id);
  assert.equal((event.payload as any).correlationId, 'correlacion-outbox');
  assert.equal(event.publicadoEn, null);
});

test('si falla el guardado del evento se revierte la creación del período', async () => {
  const dto = await fixture();
  const publisher = { publishVacacionesProgramadas: async () => { throw new Error('Fallo al guardar evento'); } };
  const service = new VacationService(new VacationRepository(), publisher as any);
  await assert.rejects(() => service.createPeriod(dto), /Fallo al guardar/);
  assert.equal(await prisma.vacationPeriod.count({ where: { empleadoId: dto.empleadoId } }), 0);
  assert.equal(await prisma.outboxEvento.count(), 0);
});

test('fallo al guardar evento de inicio revierte también el cambio de estado', async () => {
  const dto = await fixture();
  const period = await new VacationService().createPeriod(dto);
  await prisma.outboxEvento.deleteMany();
  const publisher = { publishVacacionesIniciadas: async () => { throw new Error('Fallo al guardar inicio'); } };
  const service = new VacationService(new VacationRepository(), publisher as any);
  await assert.rejects(() => service.forcePeriodStart(period.id), /Fallo al guardar inicio/);
  assert.equal((await prisma.vacationPeriod.findUniqueOrThrow({ where: { id: period.id } })).estado, 'PROGRAMADA');
  assert.equal(await prisma.outboxEvento.count(), 0);
});

test('un fallo del broker conserva el evento y el reintento entrega el mismo id y las cabeceras', async () => {
  const dto = await fixture();
  await new VacationService().createPeriod(dto);
  const pending = await prisma.outboxEvento.findFirstOrThrow();
  const traceparent = '00-11111111111111111111111111111111-2222222222222222-01';
  await prisma.outboxEvento.update({ where: { id: pending.id }, data: { headers: { traceparent } } });
  const unavailable = { enviarAlBroker: async () => { throw new Error('Broker no disponible'); } };
  await new OutboxPublisher(unavailable as any).publicarPendientes();
  const failed = await prisma.outboxEvento.findUniqueOrThrow({ where: { id: pending.id } });
  assert.equal(failed.publicadoEn, null);
  assert.equal(failed.intentos, 1);

  const connection = await amqp.connect(rabbitConfig.url);
  const channel = await connection.createChannel();
  const publisher = new RabbitMQPublisher();
  try {
    await channel.assertExchange('hr.events', 'fanout', { durable: true });
    const observer = await channel.assertQueue('', { exclusive: true });
    await channel.bindQueue(observer.queue, 'hr.events', '');
    await new OutboxPublisher(publisher).publicarPendientes();
    const message = await channel.get(observer.queue, { noAck: true });
    assert.ok(message);
    assert.equal(JSON.parse(message.content.toString()).id, pending.id);
    assert.equal(message.properties.headers?.traceparent, traceparent);
    assert.notEqual((await prisma.outboxEvento.findUniqueOrThrow({ where: { id: pending.id } })).publicadoEn, null);
    assert.equal(await channel.get(observer.queue, { noAck: true }), false);
  } finally {
    await publisher.close();
    await channel.close();
    await connection.close();
  }
});

test('dos workers concurrentes reparten las filas sin publicar una fila dos veces', { timeout: 15000 }, async () => {
  await new VacationService().createPeriod(await fixture());
  await new VacationService().createPeriod(await fixture());
  const ids: string[] = [];
  let entered!: () => void;
  let release!: () => void;
  const firstEntered = new Promise<void>(resolve => { entered = resolve; });
  const firstReleased = new Promise<void>(resolve => { release = resolve; });
  const slow = { enviarAlBroker: async (event: any) => { ids.push(event.id); entered(); await firstReleased; } };
  const fast = { enviarAlBroker: async (event: any) => { ids.push(event.id); } };
  const first = new OutboxPublisher(slow as any).publicarPendientes();
  try {
    await firstEntered;
    await new OutboxPublisher(fast as any).publicarPendientes();
    assert.equal(ids.length, 2);
    assert.equal(new Set(ids).size, 2);
  } finally {
    release();
    await first;
  }
  assert.equal(await prisma.outboxEvento.count({ where: { publicadoEn: null } }), 0);
});
