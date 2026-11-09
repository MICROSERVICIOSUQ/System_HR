"""Acceso a PostgreSQL con SQLAlchemy.

El esquema es el mismo que creó Prisma en la versión anterior del servicio: tabla
`Departamento` con columnas en camelCase entrecomilladas y la secuencia
`departments_id_seq` para generar identificadores `D001`, `D002`... No se cambia
nada de eso. La reconstrucción es del servicio, no de sus datos: una base de
datos con departamentos reales sigue funcionando sin migrar nada.
"""

from __future__ import annotations

from collections.abc import Iterator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import obtener_configuracion

_motor = None
_FabricaDeSesiones: sessionmaker[Session] | None = None


def motor():
    """Pool de conexiones, creado la primera vez que se necesita."""
    global _motor
    if _motor is None:
        config = obtener_configuracion()
        _motor = create_engine(
            config.url_base_de_datos,
            # Comprueba que la conexión del pool sigue viva antes de entregarla.
            # Sin esto, tras un reinicio de PostgreSQL la primera petición de
            # cada conexión reciclada falla con "server closed the connection",
            # que es un error que el cliente no puede hacer nada por evitar.
            pool_pre_ping=True,
            pool_size=5,
            max_overflow=5,
            # Timeouts equivalentes a los que tenía el adaptador de Prisma, para
            # que una base de datos colgada no deje peticiones esperando
            # indefinidamente.
            connect_args={"connect_timeout": 5},
            future=True,
        )
    return _motor


def fabrica_de_sesiones() -> sessionmaker[Session]:
    global _FabricaDeSesiones
    if _FabricaDeSesiones is None:
        _FabricaDeSesiones = sessionmaker(
            bind=motor(), autoflush=False, expire_on_commit=False, future=True
        )
    return _FabricaDeSesiones


def obtener_sesion() -> Iterator[Session]:
    """Dependencia de FastAPI: una sesión por petición, cerrada al terminar."""
    sesion = fabrica_de_sesiones()()
    try:
        yield sesion
    finally:
        sesion.close()
