"""Rutas de /departamentos.

Los cinco verbos del CRUD, con el mismo contrato que la versión en Node: mismas
rutas, mismos códigos de respuesta, mismos mensajes de error y misma forma del
JSON. La diferencia visible es que ahora la documentación de cada endpoint sale
de estas mismas anotaciones y se sirve en `/docs`, en lugar de vivir en un
archivo OpenAPI escrito a mano que podía quedar desincronizado del código.
"""

from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends, Path, Query, Response, status
from sqlalchemy.orm import Session

from app.clients.employees import ClienteDeEmpleados
from app.config import Configuracion, obtener_configuracion
from app.db import obtener_sesion
from app.errors import ErrorDeAplicacion
from app.repository import CAMPOS_ORDENABLES, RepositorioDeDepartamentos
from app.schemas import (
    DepartamentoActualizar,
    DepartamentoCrear,
    DepartamentoRespuesta,
    PaginaDeDepartamentos,
    RespuestaDeDependenciaNoDisponible,
    RespuestaDeError,
)
from app.service import ServicioDeDepartamentos

router = APIRouter(tags=["Departamentos"])

RESPUESTAS_DE_ERROR: dict[int | str, dict[str, Any]] = {
    400: {"model": RespuestaDeError, "description": "Datos o parámetros inválidos"},
    404: {"model": RespuestaDeError, "description": "El departamento no existe"},
    409: {"model": RespuestaDeError, "description": "Conflicto: id duplicado o departamento con empleados"},
    503: {
        "model": RespuestaDeError | RespuestaDeDependenciaNoDisponible,
        "description": "Base de datos o servicio de empleados no disponible",
    },
}


def obtener_servicio(
    sesion: Annotated[Session, Depends(obtener_sesion)],
    config: Annotated[Configuracion, Depends(obtener_configuracion)],
) -> ServicioDeDepartamentos:
    """Arma el servicio con sus dependencias.

    Se declara como dependencia de FastAPI y no se instancia dentro de cada
    manejador para que las pruebas puedan sustituirlo con `dependency_overrides`
    sin tocar base de datos ni red.
    """
    return ServicioDeDepartamentos(
        repositorio=RepositorioDeDepartamentos(sesion),
        cliente_empleados=ClienteDeEmpleados(
            url_base=config.url_empleados,
            timeout_segundos=config.timeout_empleados_segundos,
        ),
    )


ServicioInyectado = Annotated[ServicioDeDepartamentos, Depends(obtener_servicio)]


@router.get(
    "",
    summary="Listar departamentos",
    description=(
        "Devuelve todos los departamentos ordenados por fecha de creación "
        "descendente. Si se envía cualquiera de los parámetros de paginación "
        "(page, limit, sortBy, order) la respuesta cambia de un array a un "
        "objeto con `data` y `meta`."
    ),
    response_model=list[DepartamentoRespuesta] | PaginaDeDepartamentos,
    responses=RESPUESTAS_DE_ERROR,
)
def listar(
    servicio: ServicioInyectado,
    page: Annotated[int | None, Query(ge=1, description="Número de página (base 1)")] = None,
    limit: Annotated[int | None, Query(ge=1, le=100, description="Tamaño de página (máximo 100)")] = None,
    sortBy: Annotated[str | None, Query(description=f"Campo de ordenamiento: {', '.join(CAMPOS_ORDENABLES)}")] = None,
    order: Annotated[str | None, Query(description="ASC o DESC")] = None,
):
    # El listado sin paginar devuelve un array plano. Es el comportamiento de la
    # versión anterior y hay clientes que lo consumen así: employees-service
    # comprueba `len(lista) > 0` para saber si un departamento tiene empleados,
    # y notifications/profiles leen listados sin paginación.
    if page is None and limit is None and sortBy is None and order is None:
        return servicio.listar()

    campo = sortBy or "createdAt"
    if campo not in CAMPOS_ORDENABLES:
        raise ErrorDeAplicacion(
            400, f"sortBy no permitido. Valores: {', '.join(CAMPOS_ORDENABLES)}"
        )

    direccion = (order or "DESC").upper()
    if direccion not in ("ASC", "DESC"):
        raise ErrorDeAplicacion(400, "order debe ser ASC o DESC")

    return servicio.listar_pagina(
        pagina=page or 1,
        limite=limit or 10,
        ordenar_por=campo,
        orden=direccion.lower(),
    )


@router.get(
    "/{id}",
    summary="Consultar un departamento por su identificador",
    response_model=DepartamentoRespuesta,
    responses=RESPUESTAS_DE_ERROR,
)
def obtener(
    servicio: ServicioInyectado,
    id: Annotated[str, Path(min_length=1, description="Identificador del departamento", examples=["IT"])],
):
    return servicio.obtener(id)


@router.post(
    "",
    summary="Crear un departamento",
    description=(
        "Crea un departamento. Si no se envía `id`, se genera uno con la "
        "secuencia de la base de datos (D001, D002...)."
    ),
    response_model=DepartamentoRespuesta,
    status_code=status.HTTP_201_CREATED,
    responses=RESPUESTAS_DE_ERROR,
)
def crear(servicio: ServicioInyectado, datos: DepartamentoCrear):
    return servicio.crear(datos)


@router.put(
    "/{id}",
    summary="Actualizar un departamento (reemplazo parcial)",
    response_model=DepartamentoRespuesta,
    responses=RESPUESTAS_DE_ERROR,
)
def actualizar_put(
    servicio: ServicioInyectado,
    id: Annotated[str, Path(min_length=1)],
    datos: DepartamentoActualizar,
):
    return servicio.actualizar(id, datos)


@router.patch(
    "/{id}",
    summary="Actualizar un departamento",
    description="Idéntico a PUT: los dos verbos aceptan una actualización parcial.",
    response_model=DepartamentoRespuesta,
    responses=RESPUESTAS_DE_ERROR,
)
def actualizar_patch(
    servicio: ServicioInyectado,
    id: Annotated[str, Path(min_length=1)],
    datos: DepartamentoActualizar,
):
    return servicio.actualizar(id, datos)


@router.delete(
    "/{id}",
    summary="Eliminar un departamento",
    description=(
        "Borrado físico, a diferencia del retiro de empleados, que es lógico. "
        "Se rechaza con 409 si el departamento tiene empleados asociados, y con "
        "503 con `detail` si no se puede consultar a employees-service para averiguarlo: ante "
        "la duda no se borra, porque dejaría empleados apuntando a un "
        "departamento inexistente."
    ),
    status_code=status.HTTP_204_NO_CONTENT,
    responses=RESPUESTAS_DE_ERROR,
)
async def eliminar(
    servicio: ServicioInyectado,
    id: Annotated[str, Path(min_length=1)],
):
    await servicio.eliminar(id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
