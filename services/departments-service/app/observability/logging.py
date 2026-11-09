"""Logs estructurados en JSON con traceId.

El formato es el MISMO que emiten los tres servicios Java (Logback +
logstash-logback-encoder) y los tres de Node (winston):

    {"timestamp","level","service","traceId","spanId","logger","message"}

Esa uniformidad es lo que hace que una sola consulta LogQL en Grafana sirva para
los siete servicios, en tres lenguajes distintos, sin casos especiales:

    {job="docker"} | json | traceId = "..."

El traceId se lee del contexto activo de OpenTelemetry en cada registro, igual
que en los servicios Node. En Java lo pone Micrometer en el MDC y Logback lo
vuelca solo; aquí hay que hacerlo explícito con un filtro.
"""

from __future__ import annotations

import logging
import sys
from datetime import datetime, timezone

from opentelemetry import trace
from pythonjsonlogger.json import JsonFormatter

_configurado = False


class _FiltroDeContexto(logging.Filter):
    """Añade service, traceId y spanId a cada registro.

    Cuando no hay span activo —al arrancar, o en una tarea de fondo— los campos
    quedan como cadena vacía en lugar de ausentes, para que el esquema del log
    sea siempre el mismo y Loki no tenga que lidiar con documentos de forma
    variable.
    """

    def __init__(self, nombre_servicio: str) -> None:
        super().__init__()
        self.nombre_servicio = nombre_servicio

    def filter(self, record: logging.LogRecord) -> bool:
        record.service = self.nombre_servicio

        span = trace.get_current_span()
        contexto = span.get_span_context() if span else None
        if contexto and contexto.is_valid:
            # Los ids se formatean en hexadecimal con el ancho del estándar W3C
            # (32 y 16 caracteres). Sin el relleno, un traceId con ceros a la
            # izquierda no coincidiría con el que muestra Zipkin.
            record.traceId = format(contexto.trace_id, "032x")
            record.spanId = format(contexto.span_id, "016x")
        else:
            record.traceId = ""
            record.spanId = ""
        return True


class _FormatoDelEcosistema(JsonFormatter):
    """Genera exactamente los campos que esperan Promtail y Loki."""

    def add_fields(self, log_record, record, message_dict):  # type: ignore[override]
        super().add_fields(log_record, record, message_dict)
        log_record["timestamp"] = (
            datetime.fromtimestamp(record.created, tz=timezone.utc)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z")
        )
        # Los servicios Java emiten el nivel en mayúsculas; Python también, pero
        # se fija explícitamente para que no dependa de la configuración.
        log_record["level"] = record.levelname.upper()
        log_record["logger"] = record.name
        log_record["message"] = record.getMessage()
        # Claves que el formateador añade por su cuenta y que duplicarían
        # información ya presente.
        log_record.pop("taskName", None)


def configurar_logging(nombre_servicio: str, nivel: str = "INFO") -> None:
    """Deja el logging raíz emitiendo JSON por stdout.

    Solo consola: en un contenedor los logs van a stdout y es Promtail quien los
    recoge por el socket de Docker. Escribir a un archivo dentro del contenedor
    los perdería al reiniciarlo y además duplicaría el almacenamiento.
    """
    global _configurado
    if _configurado:
        return

    manejador = logging.StreamHandler(sys.stdout)
    manejador.setFormatter(_FormatoDelEcosistema())
    manejador.addFilter(_FiltroDeContexto(nombre_servicio))

    raiz = logging.getLogger()
    raiz.handlers.clear()
    raiz.addHandler(manejador)
    raiz.setLevel(getattr(logging, nivel, logging.INFO))

    # Uvicorn trae sus propios manejadores con formato de texto. Se desactivan
    # para que sus líneas también salgan en JSON por el manejador raíz; si no,
    # la mitad de los logs del contenedor serían texto plano y Loki no podría
    # filtrarlos por campo.
    for nombre in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        registrador = logging.getLogger(nombre)
        registrador.handlers.clear()
        registrador.propagate = True

    _configurado = True


def obtener_logger(nombre: str) -> logging.Logger:
    return logging.getLogger(nombre)
