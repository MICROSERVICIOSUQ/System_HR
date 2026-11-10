from __future__ import annotations

import httpx
import pytest

from app.clients.employees import (
    ClienteDeEmpleados,
    ServicioDeEmpleadosInalcanzable,
)


class ClienteHttpConFallo:
    def __init__(self, error: httpx.HTTPError) -> None:
        self.error = error

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False

    async def get(self, *_args, **_kwargs):
        raise self.error


@pytest.mark.parametrize(
    "error",
    [
        httpx.TimeoutException("timeout"),
        httpx.ConnectError("connection refused"),
    ],
)
@pytest.mark.asyncio
async def test_fallos_de_red_se_traducen_a_employees_service_inalcanzable(
    monkeypatch, error
):
    monkeypatch.setattr(
        "app.clients.employees.httpx.AsyncClient",
        lambda **_kwargs: ClienteHttpConFallo(error),
    )
    cliente = ClienteDeEmpleados(
        url_base="http://employees-service",
        timeout_segundos=1.0,
    )

    with pytest.raises(ServicioDeEmpleadosInalcanzable):
        await cliente.tiene_empleados("IT")
