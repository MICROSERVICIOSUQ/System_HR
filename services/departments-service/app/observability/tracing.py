"""Instrumentación de OpenTelemetry (Reto 8).

Este servicio es el tercer lenguaje del ecosistema, y eso lo convierte en la
mejor prueba de que la trazabilidad distribuida funciona de verdad: una petición
que entra por el API Gateway (Java) pasa por employees-service (Java), que llama
a este servicio (Python), y las tres aparecen en la misma cascada de Zipkin. Nada
de eso requiere que los servicios se conozcan entre sí — solo que los tres hablen
W3C Trace Context.

La propagación la hace el SDK: la instrumentación de FastAPI lee la cabecera
`traceparent` de la petición entrante y continúa esa traza en lugar de abrir una
nueva.
"""

from __future__ import annotations

from opentelemetry import trace
from opentelemetry.exporter.zipkin.json import ZipkinExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased

from app.config import Configuracion

_iniciado = False


def configurar_trazas(config: Configuracion) -> None:
    """Arranca el SDK y lo deja listo para instrumentar la aplicación."""
    global _iniciado
    if _iniciado:
        return

    recurso = Resource.create(
        {
            # service.name es lo que se ve en la UI de Zipkin. Sin él, el
            # servicio aparecería como "unknown_service" y la cascada sería
            # ilegible.
            "service.name": config.nombre_servicio,
            "service.version": "2.0.0",
            "deployment.environment": "development",
            # Deja constancia del lenguaje en la propia traza, que es útil
            # cuando el objetivo del reto es demostrar que es políglota.
            "telemetry.sdk.language": "python",
        }
    )

    proveedor = TracerProvider(
        resource=recurso,
        # ParentBased: si la petición entrante ya venía muestreada, se respeta esa
        # decisión. Sin esto, este servicio podría descartar su parte de una
        # traza que el gateway sí había decidido conservar, y la cascada
        # aparecería incompleta en Zipkin sin explicación aparente.
        sampler=ParentBased(root=TraceIdRatioBased(config.muestreo_trazas)),
    )

    # BatchSpanProcessor y no SimpleSpanProcessor: agrupa los spans y los envía
    # en lotes en un hilo aparte, de modo que exportar no añade latencia a la
    # petición que se está atendiendo.
    proveedor.add_span_processor(
        BatchSpanProcessor(ZipkinExporter(endpoint=config.zipkin_endpoint))
    )

    trace.set_tracer_provider(proveedor)
    _iniciado = True


def instrumentar(app, motor_bd) -> None:
    """Instrumenta FastAPI, SQLAlchemy y el cliente HTTP.

    Las tres capas, no solo la primera: el valor de la traza está en poder ver
    que de los 40 ms de una petición, 30 se fueron en una consulta. Con solo la
    instrumentación de FastAPI se vería el total y habría que adivinar el resto.
    """
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor

    FastAPIInstrumentor.instrument_app(
        app,
        # El sondeo de salud y el de métricas se ejecutan cada pocos segundos.
        # Trazarlos llenaría Zipkin de ruido sin aportar nada: no son tráfico de
        # negocio y nadie va a depurar una traza de /health.
        excluded_urls="health,metrics,docs,openapi.json,redoc",
    )
    SQLAlchemyInstrumentor().instrument(engine=motor_bd)
    # El cliente hacia employees-service: instrumentarlo es lo que propaga la
    # cabecera traceparent en la llamada saliente, de modo que la validación de
    # empleados al borrar un departamento también queda en la misma traza.
    HTTPXClientInstrumentor().instrument()
