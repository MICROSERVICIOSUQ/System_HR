"""departments-service — FastAPI.

Tercer lenguaje del ecosistema System_HR (Python 3.12), reconstruido desde la
versión en Node/Express conservando el contrato HTTP completo: mismas rutas,
mismos códigos, mismos mensajes de error y mismo formato de JSON.

Lo que aporta el cambio:

  - **Swagger UI nativo en `/docs`**, generado del propio código. La versión
    anterior mantenía el documento OpenAPI en un archivo aparte del esquema de
    validación, y la auditoría los encontró desincronizados. Aquí el mismo modelo
    Pydantic valida la petición y describe el endpoint: no pueden discrepar.
  - **`/health` con comprobación real de dependencias**, incluido
    `checks.messageBroker`, que faltaba.

Lo que se mantiene intacto del Reto 8: métricas en `/metrics` con los mismos
nombres que los servicios Node, trazas a Zipkin con propagación W3C, y logs JSON
con `traceId` en el mismo formato que los otros seis servicios.
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse, PlainTextResponse

from app.config import obtener_configuracion
from app.db import motor
from app.errors import registrar_manejadores
from app.observability.health import comprobar_salud
from app.observability.logging import configurar_logging, obtener_logger
from app.observability.metrics import crear_middleware_de_metricas, respuesta_de_metricas
from app.observability.tracing import configurar_trazas, instrumentar
from app.router import router as router_departamentos
from app.schemas import EstadoDeSalud

# La configuración se resuelve aquí, al construir la aplicación: si falta
# DATABASE_URL el proceso no arranca, en lugar de levantar y fallar en la primera
# petición. Es el mismo *fail-fast* que aplican los servicios Java con JWT_SECRET.
configuracion = obtener_configuracion()

configurar_logging(configuracion.nombre_servicio, configuracion.nivel_log)
logger = obtener_logger(__name__)

# Las trazas se configuran ANTES de crear la aplicación: la instrumentación de
# FastAPI necesita que el proveedor de trazas ya exista cuando se registra.
configurar_trazas(configuracion)


@asynccontextmanager
async def ciclo_de_vida(app: FastAPI):
    logger.info(
        "departments-service iniciado",
        extra={
            "puerto": configuracion.puerto,
            "lenguaje": "python",
            "exportador_trazas": configuracion.zipkin_endpoint,
        },
    )
    yield
    # Al parar el contenedor hay que vaciar el lote de spans pendientes. Sin
    # esto se pierden las últimas trazas, que suelen ser justo las que interesan
    # cuando un servicio se cae.
    from opentelemetry import trace

    proveedor = trace.get_tracer_provider()
    if hasattr(proveedor, "shutdown"):
        proveedor.shutdown()
    logger.info("departments-service detenido")


app = FastAPI(
    title="departments-service",
    version="2.0.0",
    description=(
        "Gestión de departamentos del sistema de RRHH. "
        "Microservicio en Python 3.12 con FastAPI y SQLAlchemy.\n\n"
        "Base de datos propia (ADR-001). No publica ni consume eventos: su "
        "comunicación es sincrónica, y `employees-service` lo consulta para "
        "validar el departamento de cada empleado."
    ),
    # Swagger UI en /docs y el contrato en /openapi.json, que es el requisito
    # del enunciado y lo que FastAPI sirve de fábrica.
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    lifespan=ciclo_de_vida,
)

# El middleware de métricas va primero para que mida la petición completa,
# incluido el tiempo que consumen los demás middlewares.
app.middleware("http")(crear_middleware_de_metricas(configuracion.nombre_servicio))

registrar_manejadores(app)

# Las dos rutas: /departamentos es la canónica del ecosistema y /departments se
# mantiene como alias en inglés, igual que hacía la versión anterior y que hace
# vacations-service con /vacaciones y /vacations. El API Gateway enruta las dos.
app.include_router(router_departamentos, prefix="/departamentos")
app.include_router(router_departamentos, prefix="/departments", include_in_schema=False)


@app.get(
    "/health",
    tags=["Observabilidad"],
    summary="Estado operativo del servicio y de sus dependencias",
    description=(
        "Devuelve 200 con `status: UP` si el servicio y su base de datos "
        "responden, y 503 con `status: DOWN` si la base de datos falla. "
        "`checks.messageBroker` se reporta como información del entorno: este "
        "servicio no publica ni consume eventos, así que un broker caído no lo "
        "deja fuera de servicio."
    ),
    response_model=EstadoDeSalud,
)
async def health():
    cuerpo, sano = await comprobar_salud(configuracion, motor())
    return JSONResponse(status_code=200 if sano else 503, content=cuerpo)


@app.get(
    "/metrics",
    tags=["Observabilidad"],
    summary="Métricas en formato Prometheus",
    description=(
        "Texto plano en formato de exposición de Prometheus. El recolector lo "
        "visita cada 15 s (modelo pull). Incluye tráfico, latencia y errores "
        "HTTP con los mismos nombres de métrica que los servicios Node, más las "
        "métricas de proceso (CPU, memoria, descriptores)."
    ),
    response_class=PlainTextResponse,
)
def metrics():
    return respuesta_de_metricas()


# La instrumentación se aplica al final, cuando la aplicación ya tiene sus rutas
# y middlewares: así los spans reflejan la cadena completa.
instrumentar(app, motor())
