"""Métricas en formato Prometheus (Reto 8).

Prometheus funciona por PULL: este módulo solo expone texto plano en `/metrics` y
es el recolector quien lo visita cada 15 s.

Los nombres son los mismos que publican los servicios Node con prom-client
(`http_requests_total`, `http_request_duration_seconds`), no los que generaría
una librería de instrumentación automática de FastAPI. Es deliberado: el
dashboard de Grafana ya consulta esos nombres, y si este servicio publicara otros
habría que duplicar cada panel por lenguaje. La alternativa —renombrar en las
consultas PromQL— habría movido el problema al dashboard en lugar de resolverlo.
"""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable

from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Counter,
    Histogram,
    generate_latest,
    multiprocess,
)
from prometheus_client.core import REGISTRY
from starlette.requests import Request
from starlette.responses import Response

peticiones_totales = Counter(
    "http_requests_total",
    "Total de peticiones HTTP atendidas",
    ["method", "route", "status", "service"],
)

duracion_peticiones = Histogram(
    "http_request_duration_seconds",
    "Duración de las peticiones HTTP en segundos",
    ["method", "route", "status", "service"],
    # Mismos baldes que los servicios Node, alineados con los SLO del README:
    # por debajo de 300 ms se considera bueno y a partir de 2 s salta la alerta
    # de latencia. Que todos los servicios compartan baldes es lo que permite
    # calcular un p95 agregado del ecosistema con histogram_quantile.
    buckets=(0.005, 0.01, 0.05, 0.1, 0.3, 0.5, 1, 2, 5),
)


def _ruta_plantilla(request: Request) -> str:
    """Devuelve el patrón de la ruta, no la ruta concreta.

    Con la ruta real (/departamentos/IT, /departamentos/RRHH...) cada
    identificador crearía una serie temporal nueva y la cardinalidad de las
    métricas crecería sin límite — el error más común al instrumentar. Con el
    patrón (/departamentos/{id}) hay una sola serie por endpoint, que es lo que
    se quiere medir.
    """
    ruta = request.scope.get("route")
    plantilla = getattr(ruta, "path", None)
    if plantilla:
        return str(plantilla)
    # Peticiones que no coincidieron con ninguna ruta: se agrupan todas juntas
    # para que un escaneo de URLs aleatorias no genere miles de series.
    return "(sin ruta)"


def crear_middleware_de_metricas(nombre_servicio: str):
    """Middleware que mide cada petición."""

    async def middleware(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        if request.url.path == "/metrics":
            return await call_next(request)

        inicio = time.perf_counter()
        try:
            respuesta = await call_next(request)
            codigo = respuesta.status_code
            return respuesta
        except Exception:
            # Una excepción no controlada acaba en un 500 para el cliente, así
            # que así es como debe contarse. Sin este except, el fallo más grave
            # sería justo el único que no aparecería en las métricas.
            codigo = 500
            raise
        finally:
            etiquetas = {
                "method": request.method,
                "route": _ruta_plantilla(request),
                "status": str(codigo),
                "service": nombre_servicio,
            }
            duracion_peticiones.labels(**etiquetas).observe(time.perf_counter() - inicio)
            peticiones_totales.labels(**etiquetas).inc()

    return middleware


def respuesta_de_metricas() -> Response:
    """Endpoint que raspa Prometheus.

    Incluye además las métricas del proceso que `prometheus_client` registra por
    su cuenta (CPU, memoria residente, descriptores de archivo, recolección de
    basura): es la cuarta Golden Signal, la de saturación, y sale gratis.
    """
    return Response(content=generate_latest(REGISTRY), media_type=CONTENT_TYPE_LATEST)
