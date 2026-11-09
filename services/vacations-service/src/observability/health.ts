import prisma from '../config/db.ts';

// El consumidor actualiza este estado al conectar y al perder la conexión.
export const brokerHealth = { connected: false };
export async function health() {
  let database = 'UP';
  try { await prisma.$queryRaw`SELECT 1`; } catch { database = 'DOWN'; }
  const messageBroker = brokerHealth.connected ? 'UP' : 'DOWN';
  return {
    status: database === 'UP' && messageBroker === 'UP' ? 'UP' : 'DOWN',
    service: 'vacations-service', checks: { database, messageBroker },
  };
}
