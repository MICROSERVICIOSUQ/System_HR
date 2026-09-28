import app from './app.ts';
import { RabbitMQConsumer } from './messaging/rabbitmq.consumer.ts';

const PORT = Number(process.env.PORT ?? 8084);

const server = app.listen(PORT, () => {
  console.log(`[VACATIONS-SERVICE] Servidor HTTP escuchando en el puerto ${PORT}`);
});

const consumer = new RabbitMQConsumer();

// Iniciar consumidor RabbitMQ en segundo plano
consumer.start().catch((err) => {
  console.error('[VACATIONS-SERVICE] Error fatal iniciando consumidor RabbitMQ:', err);
});

// Manejo de apagado ordenado
const shutdown = async () => {
  console.log('[VACATIONS-SERVICE] Recibida señal de apagado, cerrando recursos...');
  await consumer.stop();
  server.close(() => {
    console.log('[VACATIONS-SERVICE] Servidor HTTP cerrado.');
    process.exit(0);
  });
};

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
