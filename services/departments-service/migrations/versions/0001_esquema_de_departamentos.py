"""Esquema de departamentos: tabla y secuencia de identificadores.

Revision ID: 0001
Revises: None

Esta revisión reproduce exactamente el esquema que dejaron las tres migraciones
de Prisma de la versión anterior del servicio:

  1. baseline                         → tabla "Departamento"
  2. departments_sequence             → secuencia departments_id_seq
  3. require_department_description    → descripcion NOT NULL

Es IDEMPOTENTE a propósito (IF NOT EXISTS en todo). El motivo: una base de datos
ya existente, creada por Prisma, tiene la tabla pero no la tabla de versiones de
Alembic. Al ejecutar `alembic upgrade head` sobre ella, esta revisión corre y no
debe fallar ni duplicar nada — solo registrar que el esquema ya está al día. Sin
la idempotencia, migrar un entorno en funcionamiento exigiría intervención
manual, y una migración que necesita que alguien la acompañe no sirve para un
despliegue automatizado.

Los nombres van entrecomillados porque Prisma creó la tabla y las columnas en
camelCase, y PostgreSQL solo conserva las mayúsculas si se entrecomillan.
"""

from __future__ import annotations

from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS "Departamento" (
            "id"          TEXT NOT NULL,
            "nombre"      TEXT NOT NULL,
            "descripcion" TEXT NOT NULL,
            "createdAt"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            "updatedAt"   TIMESTAMP(3) NOT NULL,
            CONSTRAINT "Departamento_pkey" PRIMARY KEY ("id")
        )
        """
    )

    # La secuencia genera los identificadores D001, D002... cuando el POST no
    # trae id. Se delega en PostgreSQL porque nextval es atómico: dos peticiones
    # simultáneas nunca obtienen el mismo número, cosa que un contador en la
    # aplicación no garantiza.
    op.execute("CREATE SEQUENCE IF NOT EXISTS departments_id_seq START WITH 1")

    # Para una base creada por Prisma antes de la tercera migración, la columna
    # podría seguir admitiendo NULL. Se normaliza igual que entonces.
    op.execute(
        """
        UPDATE "Departamento"
        SET "descripcion" = 'Sin descripcion'
        WHERE "descripcion" IS NULL
        """
    )
    op.execute('ALTER TABLE "Departamento" ALTER COLUMN "descripcion" SET NOT NULL')

    op.execute(
        """
        COMMENT ON TABLE "Departamento" IS
            'Departamentos de la organizacion. Servicio en Python/FastAPI desde el Reto 9.'
        """
    )


def downgrade() -> None:
    # El downgrade borra la tabla y con ella los departamentos. Se deja
    # implementado por completitud de la herramienta, pero no es una operación
    # que tenga sentido ejecutar en un entorno con datos: los empleados
    # quedarían apuntando a departamentos inexistentes.
    op.execute('DROP TABLE IF EXISTS "Departamento"')
    op.execute("DROP SEQUENCE IF EXISTS departments_id_seq")
