"""Configuración común de las pruebas.

Las variables de entorno se fijan ANTES de importar la aplicación. No es un
detalle: `app.main` resuelve la configuración al importarse —fail-fast
deliberado, para que un contenedor mal configurado no levante— así que sin esto
las pruebas fallarían al cargarse en lugar de ejecutarse.

La URL que se inyecta es un marcador y nunca se usa: las pruebas unitarias
trabajan con dobles y las de API sustituyen la capa de datos con
`dependency_overrides`. Si algo intentara conectarse de verdad, fallaría al
conectar en lugar de contaminar una base real, que es el comportamiento que se
quiere.
"""

from __future__ import annotations

import os

os.environ.setdefault(
    "DATABASE_URL", "postgresql://pruebas:sin-conexion@localhost:1/no-se-usa"
)
os.environ.setdefault("EMPLOYEES_SERVICE_URL", "http://employees-no-se-usa:8080")
os.environ.setdefault("OTEL_SERVICE_NAME", "departments-service")
# Sin exportador de trazas: una prueba no debe depender de que exista un Zipkin
# al que enviar spans. Es la misma lección que en los servicios Java, donde el
# sender de Zipkin abría su conexión al construirse y rompía el contexto entero.
os.environ.setdefault("OTEL_TRACES_SAMPLER_ARG", "0")
os.environ.setdefault("OTEL_SDK_DISABLED", "true")
# Sin broker configurado, /health omite el check de messageBroker en lugar de
# intentar una conexión TCP que no llevaría a ningún sitio.
os.environ.pop("RABBITMQ_URL", None)
os.environ.pop("RABBITMQ_HOST", None)

import pytest  # noqa: E402 - después de fijar el entorno, a propósito


@pytest.fixture
def datos_de_departamento():
    """Cuerpo válido para crear un departamento."""
    return {
        "id": "IT",
        "nombre": "Tecnología",
        "descripcion": "Departamento de tecnología e infraestructura",
    }
