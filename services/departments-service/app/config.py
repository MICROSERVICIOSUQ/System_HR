"""Configuración del servicio, tomada del entorno.

Las variables son las mismas que recibía la versión en Node desde
docker-compose.yml, de modo que la reconstrucción en Python no obliga a tocar el
Compose ni el .env: `PORT`, `DATABASE_URL`, `RABBITMQ_URL` y
`EMPLOYEES_SERVICE_URL`.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse


def _sin_barra_final(url: str) -> str:
    return url.rstrip("/")


def _url_sqlalchemy(url: str) -> str:
    """Adapta la URL de la base de datos al dialecto de SQLAlchemy.

    docker-compose entrega `postgresql://usuario:clave@host:5432/base`, que es la
    forma que entendía Prisma. SQLAlchemy necesita saber además qué driver usar,
    y sin el sufijo elige psycopg2, que no está instalado. Se traduce aquí en
    lugar de cambiar el Compose: así la variable sigue sirviendo para los dos
    mundos y nadie tiene que recordar el detalle.
    """
    if url.startswith("postgresql+"):
        return url
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+psycopg://", 1)
    if url.startswith("postgres://"):
        return url.replace("postgres://", "postgresql+psycopg://", 1)
    return url


@dataclass(frozen=True)
class Configuracion:
    nombre_servicio: str
    puerto: int
    url_base_de_datos: str
    url_empleados: str
    broker_host: str | None
    broker_puerto: int
    zipkin_endpoint: str
    muestreo_trazas: float
    nivel_log: str
    timeout_salud_segundos: float
    timeout_empleados_segundos: float

    @staticmethod
    def desde_entorno() -> "Configuracion":
        url_bd = os.environ.get("DATABASE_URL")
        if not url_bd:
            # Fallar al arrancar y no al primer request: un servicio sin base de
            # datos no puede atender nada, y es mejor que el contenedor no
            # levante que atender peticiones rotas. Es la misma decisión que
            # toma el resto del ecosistema con JWT_SECRET.
            raise RuntimeError("DATABASE_URL no está configurada")

        # El broker no es una dependencia funcional de este servicio: no publica
        # ni consume eventos. Se extrae de RABBITMQ_URL solo para poder
        # reportarlo en /health, que es lo que pide el enunciado del Reto 8.
        url_broker = os.environ.get("RABBITMQ_URL")
        broker_host: str | None = os.environ.get("RABBITMQ_HOST")
        broker_puerto = int(os.environ.get("RABBITMQ_PORT", "5672"))
        if not broker_host and url_broker:
            partes = urlparse(url_broker)
            broker_host = partes.hostname
            if partes.port:
                broker_puerto = partes.port

        return Configuracion(
            nombre_servicio=os.environ.get("OTEL_SERVICE_NAME", "departments-service"),
            puerto=int(os.environ.get("PORT", "8081")),
            url_base_de_datos=_url_sqlalchemy(url_bd),
            url_empleados=_sin_barra_final(
                os.environ.get("EMPLOYEES_SERVICE_URL", "http://localhost:8080")
            ),
            broker_host=broker_host,
            broker_puerto=broker_puerto,
            zipkin_endpoint=os.environ.get(
                "OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://zipkin:9411/api/v2/spans"
            ),
            muestreo_trazas=float(os.environ.get("OTEL_TRACES_SAMPLER_ARG", "1.0")),
            nivel_log=os.environ.get("LOG_LEVEL", "INFO").upper(),
            timeout_salud_segundos=float(os.environ.get("HEALTH_TIMEOUT_MS", "2000")) / 1000,
            timeout_empleados_segundos=float(
                os.environ.get("EMPLOYEES_TIMEOUT_MS", "5000")
            )
            / 1000,
        )


_cache: Configuracion | None = None


def obtener_configuracion() -> Configuracion:
    """Configuración del proceso, leída una sola vez.

    Se resuelve de forma perezosa y no al importar el módulo. La diferencia
    importa: con la lectura en el import, cualquier prueba que importara el
    servicio —aunque usara dobles y no tocara la base de datos— fallaba al
    cargarse si no había `DATABASE_URL` en el entorno. Es exactamente el problema
    que tenía `vacations-service`, donde la suite unitaria no se podía ejecutar
    sin configurar una base que no usaba.

    El arranque sigue siendo *fail-fast*: `main.py` pide la configuración antes
    de montar la aplicación, así que un contenedor mal configurado no levanta.
    """
    global _cache
    if _cache is None:
        _cache = Configuracion.desde_entorno()
    return _cache


def reiniciar_configuracion() -> None:
    """Olvida la configuración cacheada. Solo para pruebas."""
    global _cache
    _cache = None
