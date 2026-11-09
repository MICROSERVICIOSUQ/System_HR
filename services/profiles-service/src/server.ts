import app from './app.ts';
import { RabbitMQConsumer } from './messaging/rabbitmq.consumer.ts';
import { logger } from './observability/logger.ts';

const PORT = Number(process.env.PORT ?? 8083);

const server = app.listen(PORT, () => {
  logger.info('[PROFILES-SERVICE] Servidor HTTP escuchando', { puerto: PORT });
});

const consumer = new RabbitMQConsumer();

// Iniciar consumidor RabbitMQ en segundo plano
consumer.start().catch((err) => {
  logger.error('[PROFILES-SERVICE] Error fatal iniciando el consumidor de RabbitMQ', {
    error: err?.message ?? String(err),
  });
});

// Manejo de apagado ordenado
const shutdown = async () => {
  logger.info('[PROFILES-SERVICE] Señal de apagado recibida, cerrando recursos');
  await consumer.stop();
  server.close(() => {
    logger.info('[PROFILES-SERVICE] Servidor HTTP cerrado');
    process.exit(0);
  });
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
