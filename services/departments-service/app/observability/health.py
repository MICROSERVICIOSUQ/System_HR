"""Sonda de salud con verificación real de dependencias (Reto 8).

Devuelve el formato que pide el enunciado:

    {"status": "UP", "service": "departments-service",
     "checks": {"database": "UP", "messageBroker": "UP"}}

con 200 cuando todo responde y 503 cuando algo no.

Dos decisiones, y las dos vienen de evitar los antipatrones que la documentación
de observabilidad señala:

1. **No devuelve `{"status":"ok"}` sin comprobar nada.** Es lo que hacía este
   servicio antes del Reto 8: con PostgreSQL caído seguía declarándose sano y
   Docker lo mantenía en el balanceador.

2. **No abre conexiones nuevas en cada sondeo.** Un health check pesado que corre
   cada 15 s acaba degradando el servicio que pretende vigilar. La base de datos
   se comprueba con `SELECT 1` sobre el pool que ya existe, y el broker con una
   conexión TCP al puerto, sin negociar AMQP ni declarar colas.

Sobre el broker: este servicio NO publica ni consume eventos, así que RabbitMQ no
es una dependencia funcional suya. Se reporta igualmente porque la auditoría lo
pedía explícitamente (`checks.messageBroker` faltaba) y porque es información
útil del entorno. Por eso su caída se refleja en el check pero **no** tumba el
estado global: declarar DOWN este servicio por un broker que no usa sería
retirarlo del balanceador sin motivo.
"""

from __future__ import annotations

import asyncio
import socket

from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.config import Configuracion
from app.observability.logging import obtener_logger

logger = obtener_logger(__name__)

ARRIBA = "UP"
ABAJO = "DOWN"


def _verificar_base_de_datos(motor: Engine) -> str:
    try:
        with motor.connect() as conexion:
            conexion.execute(text("SELECT 1"))
        return ARRIBA
    except Exception as exc:  # noqa: BLE001 - cualquier fallo es DOWN
        logger.warning("Health: base de datos no responde", extra={"detalle": str(exc)})
        return ABAJO


def _verificar_broker(host: str, puerto: int, timeout: float) -> str:
    try:
        with socket.create_connection((host, puerto), timeout=timeout):
            return ARRIBA
    except OSError as exc:
        logger.warning("Health: broker no responde", extra={"detalle": str(exc)})
        return ABAJO


async def comprobar_salud(config: Configuracion, motor: Engine) -> tuple[dict, bool]:
    """Ejecuta las comprobaciones y agrega el resultado.

    Las dos van a un hilo aparte con `to_thread` porque son bloqueantes: en un
    servidor asíncrono, un `SELECT` que tarda dos segundos en el hilo del bucle
    de eventos congela TODAS las peticiones en vuelo, no solo la del sondeo.

    Devuelve el cuerpo y si el estado global es sano.
    """
    tareas = [asyncio.to_thread(_verificar_base_de_datos, motor)]
    if config.broker_host:
        tareas.append(
            asyncio.to_thread(
                _verificar_broker,
                config.broker_host,
                config.broker_puerto,
                config.timeout_salud_segundos,
            )
        )

    resultados = await asyncio.gather(*tareas)

    checks = {"database": resultados[0]}
    if config.broker_host:
        checks["messageBroker"] = resultados[1]

    # Solo la base de datos decide el estado global: es la única dependencia sin
    # la cual este servicio no puede atender una sola petición.
    sano = checks["database"] == ARRIBA

    return (
        {
            "status": ARRIBA if sano else ABAJO,
            "service": config.nombre_servicio,
            "checks": checks,
        },
        sano,
    )
