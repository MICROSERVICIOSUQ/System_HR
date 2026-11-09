import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';
import app from '../app.ts';
import prisma from '../config/db.ts';
import { rabbitConfig } from '../config/rabbitmq.ts';
import { RabbitMQConsumer } from '../messaging/rabbitmq.consumer.ts';
import { VacationService } from '../services/vacation.service.ts';
import { brokerHealth } from '../observability/health.ts';

async function eventually(check: () => Promise<boolean>) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.fail('No se observó el resultado asíncrono en 15 segundos');
}

test('HTTP + PostgreSQL + RabbitMQ: CRUD, validaciones y scheduler', async () => {
  const connection = await amqp.connect(rabbitConfig.url);
  const channel = await connection.createChannel();
  await channel.assertExchange('hr.events', 'fanout', { durable: true });
  await channel.assertQueue('vacations.queue', { durable: true });
  await channel.bindQueue('vacations.queue', 'hr.events', '');
  const observer = await channel.assertQueue('', { exclusive: true });
  await channel.bindQueue(observer.queue, 'hr.events', '');
  const events: any[] = [];
  await channel.consume(observer.queue, msg => {
    if (msg) { events.push(JSON.parse(msg.content.toString())); channel.ack(msg); }
  });
  const consumer = new RabbitMQConsumer();
  const server = app.listen(0);
  const publisher = new (await import('../messaging/rabbitmq.publisher.ts')).RabbitMQPublisher();
  try {
    await consumer.start();
    await eventually(async () => brokerHealth.connected);
    const address = server.address() as import('node:net').AddressInfo;
    const base = `http://localhost:${address.port}`;
    const empleadoId = `IT-${randomUUID()}`;
    const emit = (type: string) => channel.publish('hr.events', '', Buffer.from(JSON.stringify({
      id: randomUUID(), type, version: '1.0', producer: 'integration-test',
      occurredAt: new Date().toISOString(), data: { id: empleadoId, nombre: 'Ana', apellido: 'Prueba', email: `${empleadoId}@example.test`, departamentoId: 'D1' },
    })));
    emit('empleado.creado');
    await eventually(async () => Boolean(await prisma.vacationBalance.findUnique({ where: { employeeId: empleadoId } })));
    const day = (offset: number) => {
      const date = new Date(); date.setUTCDate(date.getUTCDate() + offset);
      return date.toISOString().slice(0, 10);
    };
    const request = (path: string, method = 'GET', body?: unknown) => fetch(base + path, {
      method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined,
    });
    for (const path of ['/health', '/docs', '/openapi.json', '/metrics', '/vacaciones/balances', `/vacaciones/balances/${empleadoId}`]) {
      assert.equal((await request(path)).status, 200, path);
    }
    const dto = { empleadoId, fechaInicio: day(5), fechaFin: day(8) };
    const created = await request('/vacaciones', 'POST', dto);
    assert.equal(created.status, 201);
    const createdBody = await created.json() as any;
    const id = createdBody.data?.id ?? createdBody.id;
    assert.ok(id);
    assert.equal((await request(`/vacaciones/${id}`)).status, 200);
    assert.equal((await request(`/vacaciones?empleadoId=${empleadoId}`)).status, 200);
    assert.equal((await request('/vacaciones', 'POST', dto)).status, 400);
    assert.equal((await request('/vacaciones', 'POST', { ...dto, fechaFin: dto.fechaInicio })).status, 400);
    assert.equal((await request('/vacaciones', 'POST', { ...dto, fechaInicio: day(-1) })).status, 400);
    assert.equal((await request(`/vacaciones/${id}`, 'PUT', { fechaInicio: day(10), fechaFin: day(12) })).status, 200);
    assert.equal((await request(`/vacaciones/${id}`, 'DELETE')).status, 200);
    assert.equal((await prisma.vacationPeriod.findUnique({ where: { id } }))?.estado, 'CANCELADA');
    // Datos de prueba directamente en PostgreSQL para comprobar el reloj real sin esperar días.
    const active = await prisma.vacationPeriod.create({ data: { empleadoId, fechaInicio: new Date(day(-2)), fechaFin: new Date(day(0)), estado: 'EN_CURSO' } });
    const pending = await prisma.vacationPeriod.create({ data: { empleadoId, fechaInicio: new Date(day(0)), fechaFin: new Date(day(1)), estado: 'PROGRAMADA' } });
    const service = new VacationService(undefined, publisher);
    assert.deepEqual(await service.runSchedulerCycle(), { iniciadas: 1, finalizadas: 1 });
    assert.equal((await prisma.vacationPeriod.findUnique({ where: { id: active.id } }))?.estado, 'FINALIZADA');
    assert.equal((await prisma.vacationPeriod.findUnique({ where: { id: pending.id } }))?.estado, 'EN_CURSO');
    await eventually(async () => ['vacaciones.programadas', 'vacaciones.iniciadas', 'vacaciones.finalizadas'].every(type => events.some(event => event.type === type)));
    const transitions = events.filter(event => ['vacaciones.iniciadas', 'vacaciones.finalizadas'].includes(event.type));
    assert.deepEqual(transitions.map(event => event.type), ['vacaciones.finalizadas', 'vacaciones.iniciadas']);
    emit('empleado.retirado');
    await eventually(async () => (await prisma.vacationBalance.findUnique({ where: { employeeId: empleadoId } }))?.estadoEmpleado === 'RETIRADO');
    assert.equal((await request('/vacaciones', 'POST', { ...dto, fechaInicio: day(20), fechaFin: day(21) })).status, 400);
  } finally {
    await consumer.stop();
    await publisher.close();
    await new Promise<void>(resolve => server.close(() => resolve()));
    await channel.close(); await connection.close(); await prisma.$disconnect();
  }
});
