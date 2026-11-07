/**
 * Sonda de salud con verificación real de dependencias (Reto 8).
 *
 * Antes este servicio respondía `{"status":"ok","service":"departamentos"}` sin
 * comprobar nada. Eso es el antipatrón que advierte la documentación de
 * observabilidad: un health check que siempre contesta que todo va bien no
 * informa, solo tranquiliza. Con PostgreSQL caído el servicio seguía
 * declarándose sano y Docker lo mantenía en el balanceador.
 *
 * El otro extremo también es un antipatrón: un sondeo pesado que abre
 * conexiones nuevas cada pocos segundos acaba degradando el servicio que
 * pretende vigilar. De ahí las dos decisiones de abajo:
 *
 *   - La base de datos se comprueba con `SELECT 1` sobre el pool que ya existe,
 *     no abriendo una conexión nueva.
 *   - El broker se comprueba con una conexión TCP al puerto, sin negociar el
 *     protocolo AMQP ni declarar colas.
 *
 * Las dos comprobaciones llevan timeout. Sin él, una dependencia que no
 * responde —el caso que más importa detectar— dejaría la petición de salud
 * colgada y el sondeo expiraría sin diagnóstico.
 */
import net from 'node:net';
import prisma from '../config/db.ts';

export type EstadoDependencia = 'UP' | 'DOWN';

export interface ResultadoSalud {
  status: EstadoDependencia;
  service: string;
  checks: Record<string, EstadoDependencia>;
}

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'departments-service';
const TIMEOUT_MS = Number(process.env.HEALTH_TIMEOUT_MS ?? 2000);

function conTimeout<T>(promesa: Promise<T>, ms: number): Promise<T> {
  return Promise.race([
    promesa,
    new Promise<T>((_, rechazar) =>
      setTimeout(() => rechazar(new Error('timeout')), ms),
    ),
  ]);
}

async function verificarBaseDeDatos(): Promise<EstadoDependencia> {
  try {
    await conTimeout(prisma.$queryRaw`SELECT 1`, TIMEOUT_MS);
    return 'UP';
  } catch {
    return 'DOWN';
  }
}

/**
 * Comprueba que el puerto del broker acepta conexiones.
 *
 * Solo se ejecuta si el servicio tiene broker configurado: departments-service
 * es sincrónico y no publica ni consume eventos, así que incluir un check de
 * RabbitMQ en su salud sería declararlo degradado por una dependencia que no
 * usa.
 */
async function verificarBroker(): Promise<EstadoDependencia | null> {
  const host = process.env.RABBITMQ_HOST;
  if (!host) {
    return null;
  }
  const puerto = Number(process.env.RABBITMQ_PORT ?? 5672);

  return new Promise<EstadoDependencia>((resolver) => {
    const socket = new net.Socket();
    const cerrar = (estado: EstadoDependencia) => {
      socket.destroy();
      resolver(estado);
    };
    socket.setTimeout(TIMEOUT_MS);
    socket.once('connect', () => cerrar('UP'));
    socket.once('timeout', () => cerrar('DOWN'));
    socket.once('error', () => cerrar('DOWN'));
    socket.connect(puerto, host);
  });
}

/**
 * Ejecuta las comprobaciones en paralelo y agrega el resultado.
 *
 * El estado global es DOWN si cualquier dependencia lo está: un servicio que no
 * puede llegar a su base de datos no está en condiciones de atender tráfico,
 * aunque el proceso siga vivo. Esa es justamente la diferencia entre la sonda de
 * supervivencia (¿el proceso responde?) y la de disponibilidad (¿puede
 * trabajar?).
 */
export async function comprobarSalud(): Promise<ResultadoSalud> {
  const [baseDeDatos, broker] = await Promise.all([
    verificarBaseDeDatos(),
    verificarBroker(),
  ]);

  const checks: Record<string, EstadoDependencia> = { database: baseDeDatos };
  if (broker !== null) {
    checks.messageBroker = broker;
  }

  const hayFallo = Object.values(checks).some((estado) => estado === 'DOWN');

  return {
    status: hayFallo ? 'DOWN' : 'UP',
    service: NOMBRE_SERVICIO,
    checks,
  };
}
