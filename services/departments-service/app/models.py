"""Modelo de datos del departamento.

Mapea la tabla que ya existe, creada por las migraciones de Prisma de la versión
anterior. Los nombres entrecomillados son deliberados: Prisma generó la tabla y
las columnas en camelCase, y PostgreSQL los conserva con mayúsculas solo si van
entre comillas. Cambiarlos a snake_case habría sido más idiomático en Python,
pero obligaría a migrar los datos de un servicio que está en funcionamiento, y la
reconstrucción es del servicio, no de su base de datos.
"""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import DateTime, String, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


def _ahora() -> datetime:
    """UTC, pero sin información de zona.

    La columna es TIMESTAMP sin zona porque así la creó Prisma, que escribía
    siempre UTC. Un datetime con zona en una columna sin zona es un error de
    tipos esperando a ocurrir, así que se quita el tzinfo después de calcular la
    hora en UTC: el valor guardado es el mismo que escribía la versión anterior.
    """
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Departamento(Base):
    __tablename__ = "Departamento"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    nombre: Mapped[str] = mapped_column(String, nullable=False)
    descripcion: Mapped[str] = mapped_column(String, nullable=False)

    # TIMESTAMP(3) sin zona, igual que lo dejó Prisma. Se escriben en UTC desde
    # la aplicación para que no dependan de la zona del contenedor.
    createdAt: Mapped[datetime] = mapped_column(
        "createdAt",
        DateTime(timezone=False),
        nullable=False,
        server_default=text("CURRENT_TIMESTAMP"),
        default=_ahora,
    )
    updatedAt: Mapped[datetime] = mapped_column(
        "updatedAt",
        DateTime(timezone=False),
        nullable=False,
        default=_ahora,
        # onupdate replica el @updatedAt de Prisma: la columna se refresca en
        # cada UPDATE sin que el código tenga que acordarse.
        onupdate=_ahora,
    )

    def __repr__(self) -> str:  # pragma: no cover - solo para depuración
        return f"<Departamento {self.id} {self.nombre!r}>"
