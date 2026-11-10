"""Modelos de petición y respuesta.

Son el contrato de la API, y FastAPI los usa para dos cosas a la vez: validar lo
que entra y generar el OpenAPI que alimenta la Swagger UI de `/docs`. Eso resuelve
el problema que tenía la versión en Node, donde el esquema de validación (Zod) y
el documento OpenAPI eran dos archivos distintos que había que mantener
sincronizados a mano — y que, según la auditoría, se habían desincronizado.

Los mensajes de validación se conservan literalmente para no romper a ningún
cliente que los compruebe.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_serializer, model_validator


class DepartamentoCrear(BaseModel):
    """Cuerpo de POST /departamentos."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        extra="ignore",
        json_schema_extra={
            "example": {
                "id": "IT",
                "nombre": "Tecnología",
                "descripcion": "Departamento de tecnología e infraestructura",
            }
        },
    )

    # El id es opcional: si no llega, el repositorio lo genera con la secuencia
    # de PostgreSQL (D001, D002...). Es el comportamiento de la versión anterior
    # y hay clientes que dependen de las dos formas.
    id: str | None = Field(
        default=None,
        min_length=1,
        description="Identificador del departamento. Si se omite, se genera uno (D001, D002...).",
    )
    nombre: str = Field(min_length=1, description="Nombre del departamento")
    descripcion: str = Field(min_length=1, description="Descripción del departamento")


class DepartamentoActualizar(BaseModel):
    """Cuerpo de PUT y PATCH /departamentos/{id}."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        extra="ignore",
        json_schema_extra={"example": {"nombre": "Tecnología e Innovación"}},
    )

    nombre: str | None = Field(default=None, min_length=1)
    descripcion: str | None = Field(default=None, min_length=1)

    @model_validator(mode="after")
    def al_menos_un_campo(self) -> "DepartamentoActualizar":
        """Un PATCH vacío no es una actualización, es una petición sin sentido.

        Se rechaza con 400 en lugar de responder 200 sin haber cambiado nada,
        que dejaría al cliente creyendo que su cambio se aplicó.
        """
        if self.nombre is None and self.descripcion is None:
            raise ValueError("Debe indicar al menos un campo para actualizar")
        return self


class DepartamentoRespuesta(BaseModel):
    """Departamento tal como lo devuelve la API."""

    model_config = ConfigDict(from_attributes=True)

    id: str
    nombre: str
    descripcion: str
    createdAt: datetime
    updatedAt: datetime

    @field_serializer("createdAt", "updatedAt")
    def _iso_con_z(self, valor: datetime) -> str:
        """Serializa como `2026-10-28T01:13:51.367Z`.

        Es el formato exacto que devolvía la versión en Node: ISO-8601 con
        milisegundos y sufijo Z. Pydantic por defecto emitiría
        `2026-10-28T01:13:51.367000` sin la Z, que es la misma fecha pero otro
        texto — y un cliente que compare cadenas o parsee con un formato estricto
        lo notaría. La reconstrucción no debe cambiar el contrato.
        """
        return valor.isoformat(timespec="milliseconds") + "Z"


class MetaPaginacion(BaseModel):
    total: int
    page: int
    limit: int
    totalPages: int


class PaginaDeDepartamentos(BaseModel):
    """Respuesta paginada: se devuelve solo si se pidió paginación."""

    data: list[DepartamentoRespuesta]
    meta: MetaPaginacion


class CampoInvalido(BaseModel):
    field: str
    message: str


class RespuestaDeError(BaseModel):
    """Formato de error del servicio, idéntico al de la versión en Node."""

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "status": "error",
                "message": "El departamento con id IT no existe",
            }
        }
    )

    status: str = "error"
    message: str
    errors: list[CampoInvalido] | None = None


class RespuestaDeDependenciaNoDisponible(BaseModel):
    detail: str


class EstadoDeSalud(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "status": "UP",
                "service": "departments-service",
                "checks": {"database": "UP", "messageBroker": "UP"},
            }
        }
    )

    status: str
    service: str
    checks: dict[str, str]
