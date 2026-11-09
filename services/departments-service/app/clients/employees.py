"""Cliente HTTP hacia employees-service.

Se usa en un solo punto: antes de borrar un departamento, para comprobar que no
tenga empleados asociados. Esa comprobación existe porque las bases de datos
están separadas por servicio (ADR-001) y no hay una llave foránea que PostgreSQL
pueda defender: si se borrara el departamento, los empleados quedarían apuntando
a un `departamentoId` inexistente, y nadie se enteraría hasta que alguien
intentara leerlos.

El fallo se trata como 503 y no como "no tiene empleados". La diferencia es la
que separa un sistema correcto de uno que pierde datos: ante la duda, no se
borra.
"""

from __future__ import annotations

import httpx

from app.observability.logging import obtener_logger

logger = obtener_logger(__name__)


class ClienteDeEmpleados:
    def __init__(self, url_base: str, timeout_segundos: float) -> None:
        self.url_base = url_base.rstrip("/")
        self.timeout_segundos = timeout_segundos

    async def tiene_empleados(self, id_departamento: str) -> bool:
        """¿Hay algún empleado en este departamento?

        Lanza una excepción si no se puede saber. Nunca devuelve False por un
        fallo de comunicación: eso autorizaría un borrado a ciegas.
        """
        url = f"{self.url_base}/empleados"

        try:
            async with httpx.AsyncClient(timeout=self.timeout_segundos) as cliente:
                respuesta = await cliente.get(
                    url,
                    params={"departamentoId": id_departamento},
                    headers={"Accept": "application/json"},
                )
        except httpx.HTTPError as exc:
            logger.warning(
                "No se pudo consultar employees-service",
                extra={"detalle": str(exc), "departamento": id_departamento},
            )
            raise ServicioDeEmpleadosInalcanzable from exc

        if respuesta.status_code != 200:
            logger.warning(
                "employees-service respondio un codigo inesperado",
                extra={"codigo": respuesta.status_code},
            )
            raise ServicioDeEmpleadosInalcanzable

        cuerpo = respuesta.json()

        # El listado de empleados es un array cuando no se pide paginación, y un
        # objeto {data, meta} cuando sí. Se aceptan las dos formas: la versión
        # anterior solo aceptaba el array y habría fallado si alguien añadiera
        # parámetros de paginación a esta llamada.
        if isinstance(cuerpo, dict):
            empleados = cuerpo.get("data")
        else:
            empleados = cuerpo

        if not isinstance(empleados, list):
            logger.warning("La respuesta de employees-service no es un listado")
            raise ServicioDeEmpleadosInalcanzable

        return len(empleados) > 0


class ServicioDeEmpleadosInalcanzable(Exception):
    """No se pudo determinar si el departamento tiene empleados."""
