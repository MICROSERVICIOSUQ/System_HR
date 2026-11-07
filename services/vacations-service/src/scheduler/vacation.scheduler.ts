import { log } from '../observability/logger.ts';
import cron, { ScheduledTask } from 'node-cron';
import { VacationService } from '../services/vacation.service.ts';

/**
 * Tarea periódica que hace avanzar los períodos de vacaciones según el reloj.
 *
 * Sin ella el vacaciones-service queda a medias: registra períodos y publica
 * `vacaciones.programadas`, pero nada ocurre al llegar la fecha de inicio y la
 * cuenta del empleado nunca se suspende.
 *
 * Frecuencia configurable con `VACACIONES_CRON` (por defecto cada minuto, para
 * poder probar sin esperar un día). Se puede apagar con
 * `VACACIONES_SCHEDULER_ENABLED=false`.
 *
 * LIMITACIÓN CONOCIDA: esta tarea corre en cada instancia del servicio. Con una
 * sola instancia funciona; si el servicio se escala a N, las N dispararían el
 * mismo evento. Dentro de un proceso el compare-and-set del repositorio evita la
 * duplicación, y en el consumidor la deduplicación por eventId del Reto 4 mitiga
 * el daño, pero la coordinación real entre instancias exige un lock distribuido
 * (ShedLock). Está documentado en el README.
 */
export class VacationScheduler {
  private task: ScheduledTask | null = null;
  private ejecutando = false;

  private readonly expresionCron: string;
  private readonly habilitado: boolean;

  constructor(private readonly service = new VacationService()) {
    this.expresionCron = process.env.VACACIONES_CRON?.trim() || '* * * * *';
    this.habilitado = (process.env.VACACIONES_SCHEDULER_ENABLED ?? 'true') !== 'false';
  }

  start(): void {
    if (!this.habilitado) {
      log.info('[VACATIONS] [SCHEDULER] Deshabilitado por VACACIONES_SCHEDULER_ENABLED=false');
      return;
    }

    if (!cron.validate(this.expresionCron)) {
      log.error(
        `[VACATIONS] [SCHEDULER] Expresión cron inválida: '${this.expresionCron}'. El scheduler no se inicia.`
      );
      return;
    }

    this.task = cron.schedule(this.expresionCron, () => {
      void this.ejecutarCiclo();
    });

    log.info(
      `[VACATIONS] [SCHEDULER] Activo con expresión '${this.expresionCron}' (configurable en VACACIONES_CRON)`
    );
  }

  /**
   * Un ciclo. El guardia `ejecutando` evita que dos ciclos se pisen si uno tarda
   * más que el intervalo: node-cron no espera a que termine el anterior.
   */
  private async ejecutarCiclo(): Promise<void> {
    if (this.ejecutando) {
      log.warn('[VACATIONS] [SCHEDULER] Ciclo anterior aún en curso; se omite este disparo');
      return;
    }
    this.ejecutando = true;

    try {
      const { iniciadas, finalizadas } = await this.service.runSchedulerCycle();
      if (iniciadas > 0 || finalizadas > 0) {
        log.info(
          `[VACATIONS] [SCHEDULER] Ciclo completado | iniciadas: ${iniciadas} | finalizadas: ${finalizadas}`
        );
      }
    } catch (err: any) {
      // Un fallo transitorio de base de datos no debe tumbar el proceso:
      // el siguiente ciclo volverá a intentarlo.
      log.error('[VACATIONS] [SCHEDULER] Error en el ciclo:', err?.message ?? err);
    } finally {
      this.ejecutando = false;
    }
  }

  stop(): void {
    if (this.task) {
      this.task.stop();
      this.task = null;
      log.info('[VACATIONS] [SCHEDULER] Detenido.');
    }
  }
}
