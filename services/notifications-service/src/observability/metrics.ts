/**
 * Métricas en formato Prometheus (Reto 8).
 *
 * Prometheus funciona por PULL: este módulo solo expone un endpoint de texto
 * plano en /metrics y es el recolector quien lo visita cada 15 s. No hay que
 * enviar nada a ningún sitio.
 *
 * Los nombres de las métricas HTTP siguen la convención de Prometheus
 * (http_requests_total, http_request_duration_seconds) para que las consultas
 * PromQL del dashboard funcionen igual sobre los servicios Node y sobre los
 * Java, donde Micrometer publica equivalentes. Sin esa alineación habría que
 * duplicar cada panel del dashboard.
 */
import { Registry, collectDefaultMetrics, Counter, Histogram } from 'prom-client';
import type { Request, Response, NextFunction } from 'express';

const NOMBRE_SERVICIO = process.env.OTEL_SERVICE_NAME ?? 'notifications-service';

export const registro = new Registry();

// Etiqueta común a todas las métricas de este proceso: permite agrupar por
// servicio en Grafana sin depender del nombre del job de Prometheus.
registro.setDefaultLabels({ service: NOMBRE_SERVICIO });

// CPU, memoria residente, heap, event loop lag, descriptores de archivo y
// recolección de basura. Es la métrica de saturación de las cuatro Golden
// Signals, y sale gratis.
collectDefaultMetrics({ register: registro });

/**
 * Tráfico y errores: dos de las cuatro Golden Signals.
 *
 * La etiqueta `status` es el código HTTP, lo que permite calcular la tasa de
 * error con una sola consulta filtrando por 5xx.
 */
export const peticionesTotales = new Counter({
  name: 'http_requests_total',
  help: 'Total de peticiones HTTP atendidas',
  labelNames: ['method', 'route', 'status'] as const,
  registers: [registro],
});

/**
 * Latencia: la primera Golden Signal.
 *
 * Es un Histogram y no un Summary porque los histogramas se pueden agregar
 * entre instancias: con histogram_quantile se calcula el p95 de todo el
 * servicio, mientras que los percentiles de un Summary se calculan en cada
 * proceso y no se pueden sumar entre réplicas.
 */
export const duracionPeticiones = new Histogram({
  name: 'http_request_duration_seconds',
  help: 'Duración de las peticiones HTTP en segundos',
  labelNames: ['method', 'route', 'status'] as const,
  // Baldes alineados con los SLO del README: por debajo de 300 ms se considera
  // bueno, y a partir de 2 s se dispara la alerta de latencia alta.
  buckets: [0.005, 0.01, 0.05, 0.1, 0.3, 0.5, 1, 2, 5],
  registers: [registro],
});

/**
 * Middleware que mide cada petición.
 *
 * Usa `req.route?.path` en lugar de `req.path` a propósito: con la ruta real
 * (/departamentos/IT, /departamentos/RRHH...) cada identificador crearía una
 * serie temporal nueva y la cardinalidad de las métricas crecería sin límite.
 * Con el patrón de la ruta (/departamentos/:id) hay una sola serie por
 * endpoint, que es lo que se quiere medir.
 */
export function middlewareMetricas(
  req: Request,
  res: Response,
  next: NextFunction,
): void {
  if (req.path === '/metrics') {
    next();
    return;
  }

  const fin = duracionPeticiones.startTimer();

  res.on('finish', () => {
    const ruta = req.route?.path
      ? `${req.baseUrl}${req.route.path}`
      : req.baseUrl || req.path;
    const etiquetas = {
      method: req.method,
      route: ruta,
      status: String(res.statusCode),
    };
    fin(etiquetas);
    peticionesTotales.inc(etiquetas);
  });

  next();
}

/** Handler del endpoint que raspa Prometheus. */
export async function handlerMetricas(_req: Request, res: Response): Promise<void> {
  res.set('Content-Type', registro.contentType);
  res.end(await registro.metrics());
}
