import { telemetry } from './observability/tracing.ts';
import { log } from './observability/logger.ts';
import app from './app.ts';
import { RabbitMQConsumer } from './messaging/rabbitmq.consumer.ts';
import { VacationScheduler } from './scheduler/vacation.scheduler.ts';
import { OutboxPublisher } from './messaging/outbox.publisher.ts';

const PORT = Number(process.env.PORT ?? 8084);

const server = app.listen(PORT, () => {
  log.info(`[VACATIONS-SERVICE] Servidor HTTP escuchando en el puerto ${PORT}`);
});

const consumer = new RabbitMQConsumer();

// Iniciar consumidor RabbitMQ en segundo plano
consumer.start().catch((err) => {
  log.error('[VACATIONS-SERVICE] Error fatal iniciando consumidor RabbitMQ:', err);
});

// Scheduler que hace avanzar los períodos al llegar sus fechas (Reto 5)
const scheduler = new VacationScheduler();
scheduler.start();
const outbox = new OutboxPublisher();
outbox.start();

// Manejo de apagado ordenado
const shutdown = async () => {
  log.info('[VACATIONS-SERVICE] Recibida señal de apagado, cerrando recursos...');
  scheduler.stop();
  await outbox.stop();
  await consumer.stop();
  await telemetry.shutdown();
  server.close(() => {
    log.info('[VACATIONS-SERVICE] Servidor HTTP cerrado.');
    process.exit(0);
  });
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
