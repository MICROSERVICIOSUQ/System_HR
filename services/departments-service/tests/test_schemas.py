"""Pruebas de validación de los modelos de entrada.

Son el equivalente de las pruebas de esquema que tenía la versión en Node, que
según la auditoría eran lo único que existía. Se conservan porque la validación
es contrato: los mensajes de error los consumen clientes.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import DepartamentoActualizar, DepartamentoCrear, DepartamentoRespuesta


class TestCrear:
    def test_cuerpo_completo_es_valido(self):
        datos = DepartamentoCrear(
            id="IT", nombre="Tecnología", descripcion="Infraestructura"
        )
        assert datos.id == "IT"
        assert datos.nombre == "Tecnología"

    def test_el_id_es_opcional(self):
        """Sin id, el repositorio lo genera con la secuencia (D001, D002...)."""
        datos = DepartamentoCrear(nombre="Ventas", descripcion="Comercial")
        assert datos.id is None

    def test_recorta_espacios(self):
        """Un nombre con espacios alrededor no debe crear dos departamentos distintos."""
        datos = DepartamentoCrear(nombre="  Ventas  ", descripcion="  Comercial  ")
        assert datos.nombre == "Ventas"
        assert datos.descripcion == "Comercial"

    def test_el_nombre_es_obligatorio(self):
        with pytest.raises(ValidationError):
            DepartamentoCrear(descripcion="Sin nombre")

    def test_la_descripcion_es_obligatoria(self):
        with pytest.raises(ValidationError):
            DepartamentoCrear(nombre="Sin descripcion")

    @pytest.mark.parametrize("vacio", ["", "   "])
    def test_nombre_vacio_o_solo_espacios_se_rechaza(self, vacio):
        """Caso borde: con el recorte aplicado, '   ' queda vacío y debe fallar.

        Es el fallo que se cuela si se valida la longitud antes de recortar:
        '   ' tiene tres caracteres y pasaría la comprobación de longitud mínima,
        creando un departamento sin nombre visible.
        """
        with pytest.raises(ValidationError):
            DepartamentoCrear(nombre=vacio, descripcion="Comercial")

    def test_id_vacio_se_rechaza(self):
        with pytest.raises(ValidationError):
            DepartamentoCrear(id="", nombre="Ventas", descripcion="Comercial")


class TestActualizar:
    def test_solo_nombre_es_valido(self):
        datos = DepartamentoActualizar(nombre="Tecnología e Innovación")
        assert datos.nombre == "Tecnología e Innovación"
        assert datos.descripcion is None

    def test_solo_descripcion_es_valido(self):
        datos = DepartamentoActualizar(descripcion="Nueva descripcion")
        assert datos.nombre is None

    def test_cuerpo_vacio_se_rechaza(self):
        """Un PATCH sin campos no es una actualización.

        Responder 200 sin haber cambiado nada dejaría al cliente creyendo que su
        cambio se aplicó.
        """
        with pytest.raises(ValidationError) as excinfo:
            DepartamentoActualizar()
        assert "al menos un campo" in str(excinfo.value)

    def test_nombre_vacio_se_rechaza(self):
        with pytest.raises(ValidationError):
            DepartamentoActualizar(nombre="")


class TestRespuesta:
    def test_las_fechas_se_serializan_como_iso_con_z(self):
        """El formato de fecha es contrato, no detalle de implementación.

        La versión en Node devolvía `2026-10-28T01:13:51.367Z`. Pydantic por
        defecto emitiría `2026-10-28T01:13:51.367000` sin la Z: la misma fecha,
        otro texto. Un cliente que compare cadenas o parsee con un formato
        estricto notaría el cambio, y la reconstrucción no debe cambiar el
        contrato.
        """
        from datetime import datetime

        respuesta = DepartamentoRespuesta(
            id="IT",
            nombre="Tecnología",
            descripcion="Infra",
            createdAt=datetime(2026, 10, 28, 1, 13, 51, 367000),
            updatedAt=datetime(2026, 10, 28, 1, 13, 51, 367000),
        )
        cuerpo = respuesta.model_dump(mode="json")
        assert cuerpo["createdAt"] == "2026-10-28T01:13:51.367Z"
        assert cuerpo["updatedAt"].endswith("Z")
