"""Pruebas del contrato HTTP.

Ejercitan la aplicación completa con el `TestClient` de FastAPI —rutas,
validación, serialización y manejadores de error— sustituyendo únicamente la capa
de datos y el cliente remoto. Sin base de datos y sin red: lo que se verifica es
el contrato, y el contrato es exactamente lo que no debe haber cambiado al
reconstruir el servicio en otro lenguaje.

Cada aserción de código y de mensaje viene de la versión en Node. Si alguna
falla, es que la reconstrucción rompió a un cliente.
"""

from __future__ import annotations

from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from app.clients.employees import ServicioDeEmpleadosInalcanzable
from app.main import app
from app.models import Departamento
from app.router import obtener_servicio
from app.service import ServicioDeDepartamentos
from tests.test_service import ClienteFalso, RepositorioFalso, departamento


@pytest.fixture
def entorno():
    """Monta la aplicación con repositorio y cliente falsos.

    `dependency_overrides` sustituye la dependencia que construye el servicio, de
    modo que el resto de la aplicación —middlewares, manejadores de error,
    serialización— es el real.
    """
    repositorio = RepositorioFalso([departamento("IT", "Tecnología", "Infra")])
    cliente = ClienteFalso(tiene=False)

    app.dependency_overrides[obtener_servicio] = lambda: ServicioDeDepartamentos(
        repositorio=repositorio, cliente_empleados=cliente
    )
    with TestClient(app) as cliente_http:
        yield cliente_http, repositorio, cliente
    app.dependency_overrides.clear()


class TestListado:
    def test_sin_parametros_devuelve_un_array(self, entorno):
        """La forma de la respuesta es contrato.

        employees-service comprueba `len(lista) > 0` sobre esta respuesta para
        saber si un departamento tiene empleados. Si sin parámetros devolviera
        un objeto paginado, esa comprobación se rompería en silencio.
        """
        http, _, _ = entorno
        r = http.get("/departamentos")
        assert r.status_code == 200
        assert isinstance(r.json(), list)

    def test_con_paginacion_devuelve_data_y_meta(self, entorno):
        http, _, _ = entorno
        r = http.get("/departamentos?page=1&limit=10")
        assert r.status_code == 200
        cuerpo = r.json()
        assert set(cuerpo) == {"data", "meta"}
        assert set(cuerpo["meta"]) == {"total", "page", "limit", "totalPages"}

    def test_sortby_no_permitido_devuelve_400(self, entorno):
        http, _, _ = entorno
        r = http.get("/departamentos?sortBy=inyeccion")
        assert r.status_code == 400
        assert "sortBy no permitido" in r.json()["message"]

    def test_order_invalido_devuelve_400(self, entorno):
        http, _, _ = entorno
        r = http.get("/departamentos?order=lateral")
        assert r.status_code == 400
        assert r.json()["message"] == "order debe ser ASC o DESC"

    def test_limit_mayor_que_cien_devuelve_400(self, entorno):
        """Tope de página: evita que una sola petición arrastre la tabla entera."""
        http, _, _ = entorno
        assert http.get("/departamentos?limit=101").status_code == 400

    def test_el_alias_en_ingles_responde_igual(self, entorno):
        """/departments existía en la versión anterior y el gateway lo enruta."""
        http, _, _ = entorno
        assert http.get("/departments").status_code == 200


class TestConsultaPorId:
    def test_existente_devuelve_200_con_los_cinco_campos(self, entorno):
        http, _, _ = entorno
        r = http.get("/departamentos/IT")
        assert r.status_code == 200
        assert set(r.json()) == {"id", "nombre", "descripcion", "createdAt", "updatedAt"}

    def test_las_fechas_llevan_sufijo_z(self, entorno):
        http, _, _ = entorno
        cuerpo = http.get("/departamentos/IT").json()
        assert cuerpo["createdAt"].endswith("Z")

    def test_inexistente_devuelve_404_con_el_mensaje_exacto(self, entorno):
        """El mensaje y el código los consume employees-service.

        Al validar el departamento de un empleado distingue tres situaciones por
        el código: existe, no existe (404) y no se sabe (5xx). Un 400 en lugar
        del 404 abriría su circuit breaker por un departamento inexistente.
        """
        http, _, _ = entorno
        r = http.get("/departamentos/NO-EXISTE")
        assert r.status_code == 404
        assert r.json() == {
            "status": "error",
            "message": "El departamento con id NO-EXISTE no existe",
        }


class TestCreacion:
    def test_crear_devuelve_201(self, entorno):
        http, _, _ = entorno
        r = http.post(
            "/departamentos",
            json={"id": "RRHH", "nombre": "Recursos Humanos", "descripcion": "Gestión"},
        )
        assert r.status_code == 201
        assert r.json()["id"] == "RRHH"

    def test_crear_sin_id_lo_genera(self, entorno):
        http, _, _ = entorno
        r = http.post("/departamentos", json={"nombre": "Ventas", "descripcion": "Comercial"})
        assert r.status_code == 201
        assert r.json()["id"] == "D001"

    def test_sin_nombre_devuelve_400_con_el_campo(self, entorno):
        """Formato de error de validación: 400 con `errors`, no el 422 de FastAPI.

        FastAPI responde 422 con su estructura `detail`. Este servicio siempre
        respondió 400 con `errors: [{field, message}]`, y se traduce para que un
        cliente no note que el servicio cambió de lenguaje.
        """
        http, _, _ = entorno
        r = http.post("/departamentos", json={"descripcion": "Sin nombre"})
        assert r.status_code == 400
        cuerpo = r.json()
        assert cuerpo["status"] == "error"
        assert cuerpo["message"] == "Datos inválidos"
        assert any(e["field"] == "nombre" for e in cuerpo["errors"])

    def test_sin_descripcion_devuelve_400(self, entorno):
        http, _, _ = entorno
        r = http.post("/departamentos", json={"nombre": "Sin descripcion"})
        assert r.status_code == 400
        assert any(e["field"] == "descripcion" for e in r.json()["errors"])


class TestActualizacion:
    @pytest.mark.parametrize("verbo", ["put", "patch"])
    def test_los_dos_verbos_actualizan(self, entorno, verbo):
        http, _, _ = entorno
        r = getattr(http, verbo)("/departamentos/IT", json={"nombre": "Tecnología e Innovación"})
        assert r.status_code == 200
        assert r.json()["nombre"] == "Tecnología e Innovación"

    def test_cuerpo_vacio_devuelve_400(self, entorno):
        http, _, _ = entorno
        r = http.patch("/departamentos/IT", json={})
        assert r.status_code == 400
        assert "al menos un campo" in str(r.json()["errors"])

    def test_inexistente_devuelve_404(self, entorno):
        http, _, _ = entorno
        r = http.put("/departamentos/NO-EXISTE", json={"nombre": "X"})
        assert r.status_code == 404


class TestBorrado:
    def test_vacio_devuelve_204_sin_cuerpo(self, entorno):
        http, repositorio, _ = entorno
        r = http.delete("/departamentos/IT")
        assert r.status_code == 204
        assert r.content == b""
        assert repositorio.eliminados == ["IT"]

    def test_con_empleados_devuelve_409(self, entorno):
        http, repositorio, cliente = entorno
        cliente._tiene = True

        r = http.delete("/departamentos/IT")
        assert r.status_code == 409
        assert "tiene empleados asociados" in r.json()["message"]
        assert repositorio.eliminados == []

    def test_con_employees_service_caido_devuelve_503(self, entorno):
        """Ante la duda, no se borra."""
        http, repositorio, cliente = entorno
        cliente._falla = True

        r = http.delete("/departamentos/IT")
        assert r.status_code == 503
        assert repositorio.eliminados == []

    def test_inexistente_devuelve_404(self, entorno):
        http, _, _ = entorno
        assert http.delete("/departamentos/NO-EXISTE").status_code == 404


class TestObservabilidad:
    """Lo que el Reto 8 exige y la reconstrucción debe preservar."""

    def test_metrics_expone_formato_prometheus(self, entorno):
        http, _, _ = entorno
        r = http.get("/metrics")
        assert r.status_code == 200
        cuerpo = r.text
        # Los nombres son los mismos que publican los servicios Node, para que
        # el dashboard de Grafana no necesite un panel por lenguaje.
        assert "http_requests_total" in cuerpo
        assert "http_request_duration_seconds" in cuerpo

    def test_las_metricas_usan_el_patron_de_ruta_no_el_id(self, entorno):
        """Cardinalidad: /departamentos/{id} y no /departamentos/IT.

        Con la ruta concreta, cada identificador crearía una serie temporal
        nueva y las métricas crecerían sin límite. Es el error más común al
        instrumentar.
        """
        http, _, _ = entorno
        http.get("/departamentos/IT")
        cuerpo = http.get("/metrics").text
        assert "/departamentos/{id}" in cuerpo
        assert 'route="/departamentos/IT"' not in cuerpo

    def test_swagger_ui_se_sirve_en_docs(self, entorno):
        """Requisito explícito del enunciado para el 3.er lenguaje."""
        http, _, _ = entorno
        r = http.get("/docs")
        assert r.status_code == 200
        assert "swagger" in r.text.lower()

    def test_el_contrato_openapi_describe_los_cinco_verbos(self, entorno):
        http, _, _ = entorno
        contrato = http.get("/openapi.json").json()
        rutas = contrato["paths"]
        assert set(rutas["/departamentos"]) >= {"get", "post"}
        assert set(rutas["/departamentos/{id}"]) >= {"get", "put", "patch", "delete"}
