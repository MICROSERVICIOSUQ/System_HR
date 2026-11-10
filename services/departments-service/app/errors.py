"""Errores de dominio y su traducción a respuestas HTTP.

El formato de salida se conserva literalmente de la versión en Node:

    {"status": "error", "message": "...", "errors": [{"field": "...", "message": "..."}]}

No es un detalle cosmético. `employees-service` llama a este servicio para
validar el departamento de un empleado y distingue tres situaciones por el código
HTTP —existe, no existe (404), no se sabe (5xx)— para decidir entre aceptar el
alta, rechazarla o abrir el circuito. Cambiar un 404 por un 400 aquí haría que un
departamento inexistente abriera el circuit breaker de allá.
"""

from __future__ import annotations

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError, OperationalError

from app.observability.logging import obtener_logger

logger = obtener_logger(__name__)


class ErrorDeAplicacion(Exception):
    """Error con código HTTP explícito. El resto se traduce a 500."""

    def __init__(
        self,
        codigo: int,
        mensaje: str,
        errores: list[dict[str, str]] | None = None,
    ) -> None:
        super().__init__(mensaje)
        self.codigo = codigo
        self.mensaje = mensaje
        self.errores = errores


class DepartamentoNoEncontrado(ErrorDeAplicacion):
    def __init__(self, id_departamento: str) -> None:
        # Mensaje idéntico al de la versión anterior, incluido el id: hay
        # pruebas de extremo a extremo que lo comprueban literalmente.
        super().__init__(404, f"El departamento con id {id_departamento} no existe")


class DepartamentoDuplicado(ErrorDeAplicacion):
    def __init__(self) -> None:
        super().__init__(409, "Ya existe un departamento con ese identificador.")


class DepartamentoConEmpleados(ErrorDeAplicacion):
    def __init__(self, id_departamento: str) -> None:
        super().__init__(
            409,
            f"No se puede eliminar el departamento {id_departamento} "
            "porque tiene empleados asociados",
        )


class BaseDeDatosNoDisponible(ErrorDeAplicacion):
    def __init__(self) -> None:
        super().__init__(503, "La base de datos no está disponible temporalmente.")


class ServicioDeEmpleadosNoDisponible(ErrorDeAplicacion):
    def __init__(self) -> None:
        super().__init__(
            503,
            "No se pudo verificar la presencia de empleados vinculados en employees-service",
        )


def _respuesta(codigo: int, mensaje: str, errores=None) -> JSONResponse:
    cuerpo: dict[str, object] = {"status": "error", "message": mensaje}
    if errores:
        cuerpo["errors"] = errores
    return JSONResponse(status_code=codigo, content=cuerpo)


def registrar_manejadores(app: FastAPI) -> None:
    """Conecta los manejadores de error a la aplicación."""

    @app.exception_handler(ServicioDeEmpleadosNoDisponible)
    async def _de_empleados_no_disponible(
        _: Request, exc: ServicioDeEmpleadosNoDisponible
    ) -> JSONResponse:
        logger.warning(
            "No se pudo verificar la presencia de empleados",
            extra={"codigo": exc.codigo},
        )
        return JSONResponse(
            status_code=exc.codigo,
            content={"detail": exc.mensaje},
        )

    @app.exception_handler(ErrorDeAplicacion)
    async def _de_aplicacion(_: Request, exc: ErrorDeAplicacion) -> JSONResponse:
        # Los errores de dominio son parte del contrato, no incidentes: se
        # registran en nivel WARNING y sin traza de pila, que solo haría ruido.
        logger.warning(
            "Peticion rechazada",
            extra={"codigo": exc.codigo, "motivo": exc.mensaje},
        )
        return _respuesta(exc.codigo, exc.mensaje, exc.errores)

    @app.exception_handler(RequestValidationError)
    async def _de_validacion(_: Request, exc: RequestValidationError) -> JSONResponse:
        """Traduce los errores de Pydantic al formato del servicio.

        FastAPI responde 422 con su propia estructura `detail`. Este servicio
        siempre respondió 400 con `errors: [{field, message}]`, así que se
        traduce: un cliente no debería notar que el servicio cambió de lenguaje.
        """
        errores = []
        for detalle in exc.errors():
            # loc viene como ('body', 'nombre'); se descarta el primer tramo
            # para que el campo quede 'nombre' y no 'body.nombre'.
            ruta = [str(p) for p in detalle.get("loc", []) if p not in ("body", "query", "path")]
            errores.append(
                {
                    "field": ".".join(ruta) or "(body)",
                    "message": _mensaje_legible(detalle),
                }
            )
        return _respuesta(400, "Datos inválidos", errores)

    @app.exception_handler(IntegrityError)
    async def _de_integridad(_: Request, exc: IntegrityError) -> JSONResponse:
        """Violación de una restricción de la base de datos.

        La clave primaria duplicada es la única que puede producir este servicio,
        y llega aquí cuando dos peticiones crean el mismo id a la vez: la
        comprobación previa dice que no existe en las dos, y la restricción UNIQUE
        de PostgreSQL decide. Es la segunda capa de la garantía de unicidad del
        ADR-003.
        """
        logger.warning("Conflicto de integridad", extra={"detalle": str(exc.orig)})
        return _respuesta(409, "Ya existe un departamento con ese identificador.")

    @app.exception_handler(OperationalError)
    async def _de_operacion(_: Request, exc: OperationalError) -> JSONResponse:
        # La base de datos no responde: 503 y no 500. La diferencia importa para
        # quien llama, porque un 503 invita a reintentar y un 500 no.
        logger.error("Base de datos no disponible", extra={"detalle": str(exc.orig)})
        return _respuesta(503, "La base de datos no está disponible temporalmente.")

    @app.exception_handler(Exception)
    async def _inesperado(_: Request, exc: Exception) -> JSONResponse:
        # Aquí sí conviene la traza completa: es un fallo que nadie previó.
        logger.exception("Error no controlado", extra={"tipo": type(exc).__name__})
        return _respuesta(
            status.HTTP_500_INTERNAL_SERVER_ERROR, "Error interno del servidor."
        )


def _mensaje_legible(detalle: dict) -> str:
    """Convierte el error de Pydantic en el mensaje que daba la versión Node.

    Pydantic dice "String should have at least 1 character"; los clientes de este
    servicio esperaban "El nombre es obligatorio". Se traducen los casos que el
    contrato tenía definidos y el resto se deja pasar tal cual.
    """
    tipo = detalle.get("type", "")
    campo = detalle.get("loc", [None])[-1]

    if tipo in ("string_too_short", "missing"):
        return {
            "nombre": "El nombre es obligatorio",
            "descripcion": "La descripcion es obligatoria",
            "id": "El identificador no puede estar vacio",
        }.get(str(campo), detalle.get("msg", "Valor inválido"))

    mensaje = detalle.get("msg", "Valor inválido")
    # Pydantic prefija los errores de validador con "Value error, ".
    return mensaje.removeprefix("Value error, ")
