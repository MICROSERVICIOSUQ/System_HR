"""Entorno de Alembic.

Lee la URL de la base de datos de `DATABASE_URL`, la misma variable que usa la
aplicación y que inyecta docker-compose. No se escribe en alembic.ini porque eso
dejaría credenciales en el repositorio.
"""

from __future__ import annotations

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.config import _url_sqlalchemy  # noqa: PLC2701 - reutiliza la traducción de URL
from app.models import Base

import os

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

url_bd = os.environ.get("DATABASE_URL")
if not url_bd:
    raise RuntimeError("DATABASE_URL no está configurada: Alembic no sabe a qué base migrar")

config.set_main_option("sqlalchemy.url", _url_sqlalchemy(url_bd))

# Metadatos del modelo, para que `alembic revision --autogenerate` pueda comparar.
target_metadata = Base.metadata


def ejecutar_sin_conexion() -> None:
    """Genera el SQL sin conectarse (alembic upgrade --sql)."""
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def ejecutar_con_conexion() -> None:
    conectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with conectable.connect() as conexion:
        context.configure(connection=conexion, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    ejecutar_sin_conexion()
else:
    ejecutar_con_conexion()
