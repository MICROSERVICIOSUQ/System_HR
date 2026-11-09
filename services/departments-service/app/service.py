"""Reglas de negocio de departamentos.

Capa delgada a propósito: el CRUD de departamentos tiene una sola regla real —no
se borra un departamento con empleados— y el resto es persistencia. Existe de
todas formas porque es donde esa regla queda escrita una vez y se puede probar
con dobles, sin base de datos ni servidor HTTP.
"""

from __future__ import annotations

from app.clients.employees import ClienteDeEmpleados, ServicioDeEmpleadosInalcanzable
from app.errors import (
    DepartamentoConEmpleados,
    DepartamentoNoEncontrado,
    ServicioDeEmpleadosNoDisponible,
)
from app.models import Departamento
from app.observability.logging import obtener_logger
from app.repository import RepositorioDeDepartamentos
from app.schemas import DepartamentoActualizar, DepartamentoCrear

logger = obtener_logger(__name__)


class ServicioDeDepartamentos:
    def __init__(
        self,
        repositorio: RepositorioDeDepartamentos,
        cliente_empleados: ClienteDeEmpleados,
    ) -> None:
        self.repositorio = repositorio
        self.cliente_empleados = cliente_empleados

    def listar(self) -> list[Departamento]:
        return self.repositorio.listar()

    def listar_pagina(
        self, pagina: int, limite: int, ordenar_por: str, orden: str
    ) -> dict:
        total, datos = self.repositorio.listar_pagina(pagina, limite, ordenar_por, orden)
        # total_pages es 0 y no 1 cuando no hay datos: una lista vacía no tiene
        # "una página vacía", tiene cero páginas. Es el cálculo que hacía la
        # versión anterior y lo que espera quien pagine en el cliente.
        total_paginas = -(-total // limite) if total else 0
        return {
            "data": datos,
            "meta": {
                "total": total,
                "page": pagina,
                "limit": limite,
                "totalPages": total_paginas,
            },
        }

    def obtener(self, id_departamento: str) -> Departamento:
        departamento = self.repositorio.buscar_por_id(id_departamento)
        if departamento is None:
            raise DepartamentoNoEncontrado(id_departamento)
        return departamento

    def crear(self, datos: DepartamentoCrear) -> Departamento:
        id_departamento = datos.id or self.repositorio.siguiente_id_generado()
        departamento = self.repositorio.crear(
            id_departamento=id_departamento,
            nombre=datos.nombre,
            descripcion=datos.descripcion,
        )
        logger.info(
            "Departamento creado",
            extra={"departamento": departamento.id, "generado": datos.id is None},
        )
        return departamento

    def actualizar(self, id_departamento: str, datos: DepartamentoActualizar) -> Departamento:
        departamento = self.obtener(id_departamento)
        actualizado = self.repositorio.actualizar(
            departamento, datos.nombre, datos.descripcion
        )
        logger.info("Departamento actualizado", extra={"departamento": id_departamento})
        return actualizado

    async def eliminar(self, id_departamento: str) -> None:
        """Borra el departamento, salvo que tenga empleados asociados.

        El orden importa: primero se comprueba que exista (404 si no), después que
        esté vacío (409 si no), y solo entonces se borra. Si se consultara a
        employees-service antes de saber si el departamento existe, un id
        inventado provocaría una llamada HTTP innecesaria y, con el servicio de
        empleados caído, devolvería 503 en lugar del 404 que corresponde.
        """
        departamento = self.obtener(id_departamento)

        try:
            tiene_empleados = await self.cliente_empleados.tiene_empleados(id_departamento)
        except ServicioDeEmpleadosInalcanzable as exc:
            # No se puede saber si está vacío, así que no se borra. Un 503 pide
            # al cliente que reintente; borrar a ciegas dejaría empleados
            # apuntando a un departamento inexistente, y eso no se puede
            # deshacer.
            raise ServicioDeEmpleadosNoDisponible from exc

        if tiene_empleados:
            raise DepartamentoConEmpleados(id_departamento)

        self.repositorio.eliminar(departamento)
        logger.info("Departamento eliminado", extra={"departamento": id_departamento})
