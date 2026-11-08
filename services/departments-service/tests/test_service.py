"""Pruebas unitarias del servicio, con dobles.

Ninguna toca la base de datos ni la red: el repositorio y el cliente de empleados
se sustituyen por dobles escritos a mano. Es lo que pedía la auditoría —la versión
en Node solo tenía pruebas de validación HTTP— y lo que permite ejecutar la suite
sin Docker.

La regla que concentra el valor es la del borrado: un departamento con empleados
no se borra, y si no se puede averiguar, tampoco. Ese "tampoco" es el caso que un
descuido convierte en pérdida de datos.
"""

from __future__ import annotations

from datetime import datetime

import pytest

from app.clients.employees import ServicioDeEmpleadosInalcanzable
from app.errors import (
    DepartamentoConEmpleados,
    DepartamentoNoEncontrado,
    ServicioDeEmpleadosNoDisponible,
)
from app.models import Departamento
from app.schemas import DepartamentoActualizar, DepartamentoCrear
from app.service import ServicioDeDepartamentos


def departamento(id_dep="IT", nombre="Tecnología", descripcion="Infra") -> Departamento:
    d = Departamento(id=id_dep, nombre=nombre, descripcion=descripcion)
    d.createdAt = datetime(2026, 1, 1, 10, 0, 0)
    d.updatedAt = datetime(2026, 1, 1, 10, 0, 0)
    return d


class RepositorioFalso:
    """Doble del repositorio, con los datos en memoria."""

    def __init__(self, existentes: list[Departamento] | None = None) -> None:
        self.datos = {d.id: d for d in (existentes or [])}
        self.eliminados: list[str] = []
        self.ids_generados = 0

    def listar(self):
        return sorted(self.datos.values(), key=lambda d: d.createdAt, reverse=True)

    def listar_pagina(self, pagina, limite, ordenar_por, orden):
        todos = sorted(
            self.datos.values(),
            key=lambda d: getattr(d, ordenar_por),
            reverse=(orden == "desc"),
        )
        inicio = (pagina - 1) * limite
        return len(todos), todos[inicio : inicio + limite]

    def buscar_por_id(self, id_dep):
        return self.datos.get(id_dep)

    def siguiente_id_generado(self):
        self.ids_generados += 1
        return f"D{self.ids_generados:03d}"

    def crear(self, id_departamento, nombre, descripcion):
        nuevo = departamento(id_departamento, nombre, descripcion)
        self.datos[id_departamento] = nuevo
        return nuevo

    def actualizar(self, dep, nombre, descripcion):
        if nombre is not None:
            dep.nombre = nombre
        if descripcion is not None:
            dep.descripcion = descripcion
        return dep

    def eliminar(self, dep):
        self.eliminados.append(dep.id)
        self.datos.pop(dep.id, None)


class ClienteFalso:
    """Doble del cliente de empleados."""

    def __init__(self, tiene=False, falla=False) -> None:
        self._tiene = tiene
        self._falla = falla
        self.consultas: list[str] = []

    async def tiene_empleados(self, id_dep):
        self.consultas.append(id_dep)
        if self._falla:
            raise ServicioDeEmpleadosInalcanzable
        return self._tiene


def servicio(repositorio=None, cliente=None) -> ServicioDeDepartamentos:
    return ServicioDeDepartamentos(
        repositorio=repositorio or RepositorioFalso(),
        cliente_empleados=cliente or ClienteFalso(),
    )


class TestConsultas:
    def test_obtener_devuelve_el_departamento(self):
        s = servicio(RepositorioFalso([departamento("IT")]))
        assert s.obtener("IT").nombre == "Tecnología"

    def test_obtener_inexistente_lanza_404_con_el_id_en_el_mensaje(self):
        s = servicio(RepositorioFalso())
        with pytest.raises(DepartamentoNoEncontrado) as excinfo:
            s.obtener("NO-EXISTE")
        # El mensaje es contrato: hay pruebas de extremo a extremo que lo
        # comprueban literalmente.
        assert excinfo.value.mensaje == "El departamento con id NO-EXISTE no existe"
        assert excinfo.value.codigo == 404

    def test_listado_vacio_tiene_cero_paginas_no_una(self):
        """Caso borde del cálculo de paginación.

        Una lista vacía no tiene "una página vacía", tiene cero páginas. Es el
        valor que devolvía la versión anterior y el que espera quien pagine en el
        cliente: con totalPages=1 mostraría un paginador para nada.
        """
        pagina = servicio(RepositorioFalso()).listar_pagina(1, 10, "createdAt", "desc")
        assert pagina["meta"]["total"] == 0
        assert pagina["meta"]["totalPages"] == 0

    def test_total_de_paginas_redondea_hacia_arriba(self):
        repo = RepositorioFalso([departamento(f"D{i}") for i in range(11)])
        pagina = servicio(repo).listar_pagina(1, 10, "id", "asc")
        assert pagina["meta"]["total"] == 11
        assert pagina["meta"]["totalPages"] == 2


class TestCreacion:
    def test_crear_con_id_propio_lo_respeta(self):
        repo = RepositorioFalso()
        creado = servicio(repo).crear(
            DepartamentoCrear(id="IT", nombre="Tecnología", descripcion="Infra")
        )
        assert creado.id == "IT"
        assert repo.ids_generados == 0, "no debe pedir un id a la secuencia"

    def test_crear_sin_id_lo_genera_con_la_secuencia(self):
        repo = RepositorioFalso()
        creado = servicio(repo).crear(
            DepartamentoCrear(nombre="Ventas", descripcion="Comercial")
        )
        assert creado.id == "D001"
        assert repo.ids_generados == 1


class TestActualizacion:
    def test_actualiza_solo_el_campo_enviado(self):
        repo = RepositorioFalso([departamento("IT", "Tecnología", "Original")])
        actualizado = servicio(repo).actualizar(
            "IT", DepartamentoActualizar(nombre="Tecnología e Innovación")
        )
        assert actualizado.nombre == "Tecnología e Innovación"
        assert actualizado.descripcion == "Original", "la descripcion no debe tocarse"

    def test_actualizar_inexistente_lanza_404(self):
        with pytest.raises(DepartamentoNoEncontrado):
            servicio(RepositorioFalso()).actualizar(
                "NO-EXISTE", DepartamentoActualizar(nombre="X")
            )


class TestBorrado:
    """La única regla de negocio real del servicio."""

    @pytest.mark.asyncio
    async def test_borra_un_departamento_vacio(self):
        repo = RepositorioFalso([departamento("IT")])
        cliente = ClienteFalso(tiene=False)
        await servicio(repo, cliente).eliminar("IT")
        assert repo.eliminados == ["IT"]

    @pytest.mark.asyncio
    async def test_no_borra_un_departamento_con_empleados(self):
        repo = RepositorioFalso([departamento("IT")])
        cliente = ClienteFalso(tiene=True)

        with pytest.raises(DepartamentoConEmpleados) as excinfo:
            await servicio(repo, cliente).eliminar("IT")

        assert excinfo.value.codigo == 409
        assert "tiene empleados asociados" in excinfo.value.mensaje
        assert repo.eliminados == [], "no debe haber borrado nada"

    @pytest.mark.asyncio
    async def test_si_no_se_puede_saber_no_borra(self):
        """El caso que separa un sistema correcto de uno que pierde datos.

        Con employees-service caído no se puede saber si el departamento está
        vacío. La respuesta es 503 —reintenta— y NO el borrado: las bases están
        separadas por servicio y no hay llave foránea que defienda a los
        empleados, así que un borrado a ciegas los dejaría apuntando a un
        departamento inexistente, sin forma de deshacerlo.
        """
        repo = RepositorioFalso([departamento("IT")])
        cliente = ClienteFalso(falla=True)

        with pytest.raises(ServicioDeEmpleadosNoDisponible) as excinfo:
            await servicio(repo, cliente).eliminar("IT")

        assert excinfo.value.codigo == 503
        assert repo.eliminados == [], "ante la duda, no se borra"

    @pytest.mark.asyncio
    async def test_borrar_inexistente_no_consulta_a_empleados(self):
        """Primero 404, después la consulta remota.

        Si se preguntara a employees-service antes de saber si el departamento
        existe, un id inventado provocaría una llamada HTTP innecesaria y, con el
        servicio de empleados caído, devolvería 503 en lugar del 404 que
        corresponde.
        """
        cliente = ClienteFalso(falla=True)
        with pytest.raises(DepartamentoNoEncontrado):
            await servicio(RepositorioFalso(), cliente).eliminar("NO-EXISTE")
        assert cliente.consultas == [], "no debe gastar una llamada HTTP"
