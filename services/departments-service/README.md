# departments-service

Gestión de departamentos del sistema de RRHH. **Python 3.12 · FastAPI ·
SQLAlchemy · PostgreSQL.**

Es el tercer lenguaje del ecosistema. Se reconstruyó desde una versión en
Node/Express conservando el contrato HTTP completo: mismas rutas, mismos códigos,
mismos mensajes de error y mismo formato de JSON. El razonamiento y las
alternativas están en
[ADR-012](../../docs/decisions/ADR-012-departments-en-python.md).

```
services/departments-service/
├── Dockerfile                 Multietapa: dependencias aparte del código
├── requirements.txt           Versiones fijas, no rangos
├── requirements-dev.txt       Solo pruebas; no entra en la imagen final
├── pytest.ini / .coveragerc   Umbral del 70 % que rompe el comando
├── alembic.ini
├── migrations/                Migraciones versionadas (ADR-002)
│   └── versions/
│       └── 0001_esquema_de_departamentos.py
├── app/
│   ├── main.py                Aplicación, /health, /metrics, Swagger en /docs
│   ├── config.py              Variables de entorno
│   ├── db.py                  Pool de conexiones
│   ├── models.py              Tabla "Departamento"
│   ├── schemas.py             Modelos Pydantic: validan Y documentan
│   ├── router.py              Los cinco verbos del CRUD
│   ├── service.py             Reglas de negocio
│   ├── repository.py          Acceso a datos
│   ├── errors.py              Errores de dominio y su traducción a HTTP
│   ├── clients/employees.py   Cliente hacia employees-service
│   └── observability/         Trazas, métricas, logs y salud
└── tests/                     50 pruebas, 80 % de cobertura
```

---

## La API

| Verbo | Ruta | Respuesta |
|---|---|---|
| GET | `/departamentos` | 200, array ordenado por `createdAt` descendente |
| GET | `/departamentos?page=1&limit=10&sortBy=nombre&order=ASC` | 200, `{data, meta}` |
| GET | `/departamentos/{id}` | 200 · 404 |
| POST | `/departamentos` | 201 · 400 · 409 |
| PUT/PATCH | `/departamentos/{id}` | 200 · 400 · 404 |
| DELETE | `/departamentos/{id}` | 204 · 404 · **409** · **503** |

`/departments` existe como alias en inglés, igual que `vacations-service` sirve
`/vacaciones` y `/vacations`. El API Gateway enruta las dos.

**La forma de la respuesta cambia con la paginación**: sin parámetros devuelve un
array; con cualquiera de `page`, `limit`, `sortBy` u `order`, un objeto con `data`
y `meta`. No es un capricho: `employees-service` comprueba `len(lista) > 0` sobre
el listado para saber si un departamento tiene empleados, y si sin parámetros
devolviera un objeto, esa comprobación se rompería en silencio.

### El `id` se puede omitir

```json
{ "nombre": "Ventas", "descripcion": "Área comercial" }
```

Sin `id`, lo genera la secuencia `departments_id_seq` de PostgreSQL con el
formato `D001`, `D002`... Se delega en la base de datos porque `nextval` es
atómico: dos peticiones simultáneas nunca obtienen el mismo número, cosa que un
contador en la aplicación no garantiza.

### El borrado y las llaves foráneas que no existen

`DELETE /departamentos/{id}` es un **borrado físico**, a diferencia del retiro de
empleados, que es lógico. Un empleado conserva su historial para auditoría; un
departamento o está en el organigrama o no.

Lo que sí protege es otra cosa:

```
404  el departamento no existe
409  tiene empleados asociados
503  no se pudo consultar a employees-service
```

Las bases de datos están separadas por servicio (ADR-001), así que **no hay una
llave foránea que PostgreSQL pueda defender**: si se borrara el departamento, los
empleados quedarían apuntando a un `departamentoId` inexistente y nadie se
enteraría hasta que alguien intentara leerlos. Por eso el servicio consulta a
`employees-service` antes de borrar.

El 503 es la parte que importa: si no se puede saber si está vacío, **no se
borra**. Devolver "no tiene empleados" ante un fallo de comunicación autorizaría
un borrado a ciegas que no se puede deshacer.

El orden también es deliberado: primero 404, después la consulta remota. Si se
preguntara a employees-service antes de saber si el departamento existe, un id
inventado gastaría una llamada HTTP y, con el servicio de empleados caído,
devolvería 503 en lugar del 404 que corresponde.

### Formato de error

```json
{ "status": "error", "message": "El departamento con id IT no existe" }
```

```json
{ "status": "error", "message": "Datos inválidos",
  "errors": [{ "field": "nombre", "message": "El nombre es obligatorio" }] }
```

Los errores de validación responden **400, no el 422 de FastAPI**. El 422 es más
correcto en el vocabulario HTTP, pero este servicio siempre respondió 400 y el
objetivo de la reconstrucción es que ningún cliente note que cambió de lenguaje.

---

## Documentación: `/docs`

FastAPI sirve la Swagger UI en `/docs` y el contrato en `/openapi.json`, generados
a partir de **los mismos modelos Pydantic que validan la petición**.

Eso resuelve un defecto concreto de la versión anterior: el documento OpenAPI
vivía en `src/docs/openapi.ts`, escrito a mano, separado del esquema de
validación (Zod), y la auditoría los encontró desincronizados. Aquí no puede
pasar porque no hay dos fuentes.

El servicio usa `expose` y no `ports` (ADR-008: impide esquivar la validación del
JWT llamándolo directo), así que `/docs` solo es alcanzable dentro de la red:

```bash
docker compose exec departments-service curl -s localhost:8081/docs
```

Desde el navegador, el contrato se consulta agregado en el gateway:

```
http://localhost:8080/swagger-ui.html          → desplegable con los servicios
http://localhost:8080/v3/api-docs/departamentos → el contrato de este servicio
```

---

## Observabilidad

| Pilar | Endpoint / mecanismo |
|---|---|
| Métricas | `/metrics` — `prometheus-client`, modelo pull |
| Trazas | OpenTelemetry → Zipkin, propagación W3C |
| Logs | JSON por stdout con `traceId`, recogidos por Promtail |
| Salud | `/health` con comprobación real de dependencias |

**Las métricas usan los nombres de los servicios Node** —`http_requests_total`,
`http_request_duration_seconds`— y no los que generaría una librería automática
de FastAPI. Si publicara otros, el dashboard de Grafana necesitaría un panel por
lenguaje.

La etiqueta de ruta es el **patrón** (`/departamentos/{id}`) y no la ruta
concreta. Con la ruta real, cada identificador crearía una serie temporal nueva y
la cardinalidad crecería sin límite: es el error más común al instrumentar.

### `/health`

```json
{ "status": "UP", "service": "departments-service",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

200 si está sano, **503** si la base de datos falla. Antes devolvía
`{"status":"ok"}` sin comprobar nada: con PostgreSQL caído el servicio seguía
declarándose sano y Docker lo mantenía en el balanceador.

Dos decisiones, las dos para evitar los antipatrones conocidos:

- **No abre conexiones nuevas en cada sondeo.** La base se comprueba con
  `SELECT 1` sobre el pool existente y el broker con una conexión TCP al puerto,
  sin negociar AMQP. Un health check pesado que corre cada 15 s degrada el
  servicio que pretende vigilar.
- **`messageBroker` no decide el estado global.** Este servicio no publica ni
  consume eventos, así que RabbitMQ no es una dependencia funcional suya. Se
  reporta porque la auditoría lo pedía y porque es información útil, pero
  declararlo DOWN por un broker que no usa lo retiraría del balanceador sin
  motivo.

### El tercer lenguaje en la traza

Es lo que hace a este servicio interesante para el Reto 8: una petición de
onboarding produce **48 spans en 7 servicios** con los tres lenguajes en una sola
cascada de Zipkin.

```
api-gateway (Java)
 └─ employees-service (Java)
     └─ departments-service (PYTHON)   ← get /departamentos/{id}
         └─ select gestion_departamentos
```

Ninguno de los tres sabe en qué lenguaje están los otros. Lo único que comparten
es la cabecera `traceparent` del estándar W3C Trace Context.

---

## Ejecutar

### Con Docker (lo normal)

```bash
docker compose up -d departments-service
```

El contenedor aplica las migraciones al arrancar y después levanta uvicorn.

### En local

```bash
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
```

```bash
DATABASE_URL=postgresql://postgres:postgres@localhost:5434/gestion_departamentos .venv/Scripts/python.exe -m uvicorn app.main:app --reload --port 8081
```

### Pruebas

```bash
.venv/Scripts/python.exe -m pytest
```

```
50 passed
TOTAL  328 stmts  65 miss  80%
Required test coverage of 70% reached. Total coverage: 80.18%
```

No necesitan base de datos ni red: el repositorio y el cliente remoto se
sustituyen con dobles, y las pruebas de contrato usan el `TestClient` de FastAPI
reemplazando solo la capa de datos con `dependency_overrides`.

El umbral del 70 % **rompe el comando** si la cobertura baja, igual que el gate de
JaCoCo en `employees-service`. Un umbral que no falla el build es una sugerencia.

Quedan fuera de la medición el arranque, el pool de conexiones y la
instrumentación, con el mismo criterio que en los otros servicios: se mide la
lógica —servicio, repositorio, esquemas, errores— y la observabilidad se verifica
de extremo a extremo en Zipkin, Prometheus y Grafana.

---

## Migraciones

Alembic sustituye a Prisma Migrate. El ADR-002 exige migraciones versionadas, y
esa decisión no cambia porque cambie el lenguaje.

```bash
docker compose exec departments-service alembic current
docker compose exec departments-service alembic history
```

La revisión inicial es **idempotente** (`IF NOT EXISTS` en todo). Una base creada
por Prisma tiene la tabla pero no la tabla de versiones de Alembic: al ejecutar
`alembic upgrade head` sobre ella, la revisión corre, no cambia nada y solo
registra que el esquema está al día. Sin eso, migrar un entorno en funcionamiento
exigiría intervención manual.

**El esquema conserva los nombres de Prisma**: tabla `"Departamento"` y columnas
en camelCase entrecomilladas. snake_case es la convención en Python, pero
renombrar obligaría a migrar los datos de un servicio en funcionamiento. La
reconstrucción es del servicio, no de su base.

---

## Variables de entorno

Las mismas que recibía la versión en Node, así que el `docker-compose.yml` y el
`.env` no cambiaron.

| Variable | Por omisión | Para qué |
|---|---|---|
| `PORT` | `8081` | Puerto de escucha |
| `DATABASE_URL` | — (**obligatoria**) | PostgreSQL. Sin ella el proceso no arranca |
| `EMPLOYEES_SERVICE_URL` | `http://localhost:8080` | Para validar antes de borrar |
| `RABBITMQ_URL` | — | Solo para reportar `checks.messageBroker` |
| `OTEL_SERVICE_NAME` | `departments-service` | Nombre en Zipkin y en las métricas |
| `OTEL_EXPORTER_ZIPKIN_ENDPOINT` | `http://zipkin:9411/api/v2/spans` | Destino de los spans |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Muestreo: 100 % en desarrollo |
| `LOG_LEVEL` | `INFO` | |

`DATABASE_URL` se traduce de `postgresql://` a `postgresql+psycopg://` en
`config.py`. docker-compose entrega la primera forma, que es la que entendía
Prisma, y SQLAlchemy necesita saber qué driver usar. Se adapta en el código para
no tocar el Compose.

La configuración se resuelve de forma **perezosa**, no al importar el módulo: con
la lectura en el import, cualquier prueba que importara el servicio fallaba al
cargarse si no había `DATABASE_URL`, aunque usara dobles y no tocara la base. El
arranque sigue siendo *fail-fast* porque `main.py` pide la configuración antes de
montar la aplicación.

---

## Probar con Postman

Colección `departamentos-service` + entorno `system-hr`. Doce peticiones, 24
aserciones, verificadas con Newman.

La primera es **`0. Login ADMIN`** y es obligatoria: desde el Reto 5 el gateway
valida un JWT en toda ruta que no sea pública, y sin token las demás responden
401.
