/**
 * Arranque del SDK de OpenTelemetry (Reto 8).
 *
 * IMPORTANTE: este módulo debe cargarse ANTES que cualquier código de la
 * aplicación. Por eso el Dockerfile arranca con
 *
 *     npx tsx --import ./src/observability/tracing.ts src/server.ts
 *
 * y no con un import normal dentro de app.ts. La instrumentación automática
 * funciona parcheando los módulos (http, express, pg, amqplib) en el momento en
 * que se cargan; si Express ya está cargado cuando arranca el SDK, el parche
 * llega tarde y no se genera ningún span.
 *
 * Qué aporta la instrumentación automática:
 *   - Un span por cada petición HTTP entrante (servidor) y saliente (cliente).
 *   - Un span por cada consulta a PostgreSQL vía pg.
 *   - Propagación de W3C Trace Context en las cabeceras, en los dos sentidos.
 *
 * Esto último es la clave del reto: cuando el api-gateway (Java) llama a este
 * servicio (Node), el traceId viaja en la cabecera `traceparent` y el SDK lo
 * recoge solo. El resultado es una única cascada en Zipkin que atraviesa los dos
 * lenguajes, sin que ninguno de los dos sepa en qué está escrito el otro.
 */
import { NodeSDK } from '@opentelemetry/sdk-node';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { ZipkinExporter } from '@opentelemetry/exporter-zipkin';
import { Resource } from '@opentelemetry/resources';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} from '@opentelemetry/semantic-conventions';

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'notifications-service';

const sdk = new NodeSDK({
  // El service.name es lo que se ve en la UI de Zipkin. Sin él, todos los
  // servicios aparecerían como "unknown_service:node" y la cascada sería
  // ilegible.
  resource: new Resource({
    [ATTR_SERVICE_NAME]: NOMBRE_SERVICIO,
    [ATTR_SERVICE_VERSION]: process.env.SERVICE_VERSION ?? '1.0.0',
    'deployment.environment': process.env.NODE_ENV ?? 'development',
  }),

  traceExporter: new ZipkinExporter({
    serviceName: NOMBRE_SERVICIO,
    // Nombre de red de Docker, nunca localhost: dentro del contenedor
    // localhost sería este mismo proceso.
    url:
      process.env.OTEL_EXPORTER_ZIPKIN_ENDPOINT ??
      'http://zipkin:9411/api/v2/spans',
  }),

  instrumentations: [
    getNodeAutoInstrumentations({
      // El sondeo de salud y el de métricas se ejecutan cada pocos segundos.
      // Trazarlos llenaría Zipkin de ruido sin aportar nada: no son tráfico de
      // negocio y nadie va a depurar una traza de /health.
      '@opentelemetry/instrumentation-http': {
        ignoreIncomingRequestHook: (peticion) => {
          const ruta = peticion.url ?? '';
          return ['/health', '/metrics', '/docs', '/favicon.ico'].some((r) =>
            ruta.startsWith(r),
          );
        },
      },
      // Lecturas de disco y de DNS generan muchísimos spans de muy poco valor.
      '@opentelemetry/instrumentation-fs': { enabled: false },
      '@opentelemetry/instrumentation-dns': { enabled: false },
    }),
  ],
});

// El muestreo no se configura aquí a propósito: el SDK respeta las variables
// OTEL_TRACES_SAMPLER y OTEL_TRACES_SAMPLER_ARG, de modo que se puede pasar de
// 100% en desarrollo a 10% en producción sin reconstruir la imagen.
sdk.start();

console.log(
  JSON.stringify({
    timestamp: new Date().toISOString(),
    level: 'INFO',
    service: NOMBRE_SERVICIO,
    message: 'OpenTelemetry iniciado',
    exporter:
      process.env.OTEL_EXPORTER_ZIPKIN_ENDPOINT ??
      'http://zipkin:9411/api/v2/spans',
  }),
);

// Al parar el contenedor, vaciar el lote de spans pendientes. Sin esto se
// pierden las últimas trazas, que suelen ser justo las que interesan cuando un
// servicio se cae.
const apagar = () => {
  sdk
    .shutdown()
    .catch((error) => console.error('Error al cerrar OpenTelemetry:', error))
    .finally(() => process.exit(0));
};

process.on('SIGTERM', apagar);
process.on('SIGINT', apagar);
