import prisma from '../config/db.ts';
import { RabbitMQPublisher, type EventEnvelope, type VacacionesProgramadasEvent } from './rabbitmq.publisher.ts';
import { log } from '../observability/logger.ts';

interface PendingEvent {
  id: string;
  payload: EventEnvelope<VacacionesProgramadasEvent>;
  headers: Record<string, unknown>;
}

/** Cada instancia toma una fila libre y la mantiene bloqueada hasta confirmar el envío. */
export class OutboxPublisher {
  private timer?: ReturnType<typeof setInterval>;
  private running?: Promise<void>;

  constructor(private publisher = new RabbitMQPublisher()) {}

  start(): void {
    this.timer = setInterval(() => {
      void this.publicarPendientes().catch(error => log.error('[OUTBOX] Falló el ciclo:', error.message));
    }, 1000);
  }

  publicarPendientes(): Promise<void> {
    if (!this.running) {
      this.running = this.drain().finally(() => { this.running = undefined; });
    }
    return this.running;
  }

  private async drain(): Promise<void> {
    for (let count = 0; count < 50; count++) {
      const sent = await prisma.$transaction(async transaction => {
        const [event] = await transaction.$queryRaw<PendingEvent[]>`
          SELECT id, payload, headers FROM outbox_evento
          WHERE publicado_en IS NULL ORDER BY secuencia
          LIMIT 1 FOR UPDATE SKIP LOCKED`;
        if (!event) return false;
        try {
          await this.publisher.enviarAlBroker(event.payload, event.headers);
          await transaction.outboxEvento.update({
            where: { id: event.id }, data: { publicadoEn: new Date(), ultimoError: null },
          });
          return true;
        } catch (error) {
          await transaction.outboxEvento.update({ where: { id: event.id }, data: {
            intentos: { increment: 1 }, ultimoError: (error as Error).message.slice(0, 500),
          } });
          log.warn(`[OUTBOX] Evento ${event.id} pendiente de reintento`);
          return false;
        }
      }, { timeout: 15000 });
      if (!sent) break;
    }
  }

  async stop(): Promise<void> {
    clearInterval(this.timer);
    await this.running;
    await this.publisher.close();
  }
}
