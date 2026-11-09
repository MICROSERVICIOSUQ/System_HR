/**
 * Logger estructurado en JSON con traceId (Reto 8).
 *
 * El campo traceId es lo que convierte un log aislado en algo útil. Sin él,
 * encontrar un ERROR en Loki solo dice que algo falló; con él se salta a la
 * traza completa en Zipkin y se ve la petición entera que lo provocó, incluidos
 * los servicios anteriores y posteriores de la cadena.
 *
 * A diferencia de los servicios Java —donde Micrometer Tracing pone el traceId
 * en el MDC y Logback lo vuelca solo— aquí hay que leerlo del contexto activo de
 * OpenTelemetry en cada registro. Es lo que hace el formato de abajo.
 *
 * El formato de salida es idéntico al de los servicios Java a propósito: una
 * misma consulta LogQL en Grafana sirve para los siete servicios.
 */
import winston from 'winston';
import { trace } from '@opentelemetry/api';

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'profiles-service';

/**
 * Añade traceId y spanId del span activo, si hay uno.
 *
 * Cuando el log se emite fuera de una petición (al arrancar, o desde una tarea
 * programada sin span) los campos quedan vacíos en lugar de ausentes, para que
 * el esquema del log sea siempre el mismo y Loki no tenga que lidiar con
 * documentos de forma variable.
 */
const conContextoDeTraza = winston.format((info) => {
  const span = trace.getActiveSpan();
  const contexto = span?.spanContext();
  info.traceId = contexto?.traceId ?? '';
  info.spanId = contexto?.spanId ?? '';
  return info;
});

/**
 * Agrupa los datos del registro bajo la clave `context`.
 *
 * La auditoría exige `timestamp`, `level`, `service`, `context` y `traceId`, y
 * `context` era el que faltaba. No es un adorno: sin él, cada campo que se pasa
 * al logger (`empleadoId`, `eventId`, `eventType`…) aparece en la raíz del
 * documento, y Loki acaba con un esquema distinto por tipo de mensaje. Dentro de
 * `context` el documento tiene siempre la misma forma y una consulta puede
 * filtrar por `context_empleadoId` sin saber qué mensaje la produjo.
 *
 * Se emite siempre, aunque esté vacío, por la misma razón que traceId: un campo
 * ausente obliga a comprobar su existencia en cada consulta. Es también lo que
 * hace el servicio en Go, para que los siete emitan la misma forma.
 */
const PROPIAS_DE_WINSTON = new Set([
  'level',
  'message',
  'timestamp',
  'service',
  'traceId',
  'spanId',
  'stack',
  'context',
  Symbol.for('level') as unknown as string,
  Symbol.for('message') as unknown as string,
  Symbol.for('splat') as unknown as string,
]);

/**
 * Campos que inyecta la instrumentación automática de OpenTelemetry para
 * winston. Son los mismos valores que ya se emiten como traceId y spanId, en
 * snake_case, así que se descartan: dejarlos duplicaría la correlación con dos
 * nombres distintos y una consulta en Loki tendría que saber cuál mirar.
 */
const DUPLICADOS_DE_OTEL = ['trace_id', 'span_id', 'trace_flags'];

const conCampoContext = winston.format((info) => {
  for (const clave of DUPLICADOS_DE_OTEL) {
    delete (info as Record<string, unknown>)[clave];
  }

  const contexto: Record<string, unknown> =
    info.context && typeof info.context === 'object'
      ? { ...(info.context as Record<string, unknown>) }
      : {};

  for (const clave of Object.keys(info)) {
    if (PROPIAS_DE_WINSTON.has(clave)) continue;
    contexto[clave] = (info as Record<string, unknown>)[clave];
    delete (info as Record<string, unknown>)[clave];
  }

  info.context = contexto;
  return info;
});

export const logger = winston.createLogger({
  level: process.env.LOG_LEVEL ?? 'info',
  defaultMeta: { service: NOMBRE_SERVICIO },
  format: winston.format.combine(
    winston.format.timestamp({ format: 'YYYY-MM-DDTHH:mm:ss.SSS[Z]' }),
    // Winston usa minúsculas ('info'); los servicios Java emiten 'INFO'.
    // Unificar aquí evita que una consulta por level tenga que contemplar las
    // dos formas.
    winston.format((info) => {
      info.level = info.level.toUpperCase();
      return info;
    })(),
    conContextoDeTraza(),
    winston.format.errors({ stack: true }),
    // Después de errors({stack}) para que el stack quede en la raíz y no dentro
    // de context: es un campo propio del registro, no un dato de negocio.
    conCampoContext(),
    winston.format.json(),
  ),
  // Solo consola: en un contenedor los logs van a stdout y es Promtail quien
  // los recoge. Escribir a un archivo dentro del contenedor los perdería al
  // reiniciarlo y además duplicaría el almacenamiento.
  transports: [new winston.transports.Console()],
});
