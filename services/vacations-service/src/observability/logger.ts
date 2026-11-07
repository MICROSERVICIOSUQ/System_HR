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

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'vacations-service';

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
    winston.format.json(),
  ),
  // Solo consola: en un contenedor los logs van a stdout y es Promtail quien
  // los recoge. Escribir a un archivo dentro del contenedor los perdería al
  // reiniciarlo y además duplicaría el almacenamiento.
  transports: [new winston.transports.Console()],
});
