# ADR-012: Reconstruir departments-service en Python (FastAPI) conservando su contrato

## Estado

Aceptada — 2026-11-08

## Contexto

La rúbrica del proyecto exige al menos cuatro lenguajes de programación
distintos. El ecosistema tenía dos: Java (api-gateway, auth-service,
employees-service) y TypeScript (departments, notifications, profiles,
vacations). La auditoría estática marcó la diversidad tecnológica como
**[PENDIENTE]** y propuso migrar departments a Python y notifications a Go.

`departments-service` es el candidato natural, y no por ser el más fácil de
reescribir, sino por tres razones concretas:

1. **Su lógica de negocio es mínima.** Cinco verbos de CRUD y una sola regla real
   —no se borra un departamento con empleados—. Reescribir un servicio con
   reglas densas por cumplir un requisito de rúbrica sería cambiar riesgo por
   nada.
2. **No participa en la mensajería.** No publica ni consume eventos, así que la
   reescritura no toca el contrato de eventos ni la idempotencia de los
   consumidores.
3. **La auditoría encontró un defecto real en él**, no solo el lenguaje: su
   documento OpenAPI vivía en un archivo escrito a mano (`src/docs/openapi.ts`),
   separado del esquema de validación (Zod), y los dos se habían
   desincronizado. FastAPI elimina esa clase de error por construcción.

El riesgo de la operación no está en escribir Python, está en lo que depende de
este servicio:

- `employees-service` lo llama por HTTP para validar el departamento de cada
  empleado, y **distingue tres situaciones por el código de respuesta**: existe,
  no existe (404) y no se sabe (5xx). Según cuál sea, acepta el alta, la rechaza
  o abre su circuit breaker.
- El API Gateway enruta `/departamentos` y `/departments`, y agrega su contrato
  OpenAPI en la Swagger UI del borde.
- La suite BDD crea un departamento como precondición de 32 de sus 39
  escenarios.
- Prometheus lo raspa, Promtail recoge sus logs y Zipkin recibe sus trazas.
- Su base de datos tiene departamentos reales creados por el servicio anterior.

## Decisión

Se reescribe el servicio en **Python 3.12 con FastAPI y SQLAlchemy**,
**preservando el contrato HTTP byte a byte** y manteniendo intacta la base de
datos.

### Lo que no cambia

| Elemento | Decisión |
|---|---|
| Rutas | `/departamentos` y `/departments`, los cinco verbos |
| Códigos | 201, 200, 204, 400, 404, 409, 503 iguales |
| Mensajes de error | literales, incluido `El departamento con id {id} no existe` |
| Formato de error | `{"status":"error","message":...,"errors":[{field,message}]}` |
| Formato de fecha | ISO-8601 con milisegundos y sufijo `Z` |
| Forma del listado | array sin paginación, `{data, meta}` con ella |
| Esquema de la base | tabla `"Departamento"` en camelCase y secuencia `departments_id_seq` |
| Variables de entorno | las mismas: `PORT`, `DATABASE_URL`, `RABBITMQ_URL`, `EMPLOYEES_SERVICE_URL` |

Tres de esos puntos merecen explicación porque la opción idiomática era la
contraria:

- **La tabla sigue llamándose `"Departamento"` con columnas en camelCase.**
  snake_case es la convención en Python y en PostgreSQL, pero renombrar
  obligaría a migrar los datos de un servicio en funcionamiento. La
  reconstrucción es del servicio, no de su base.
- **Las fechas se serializan con sufijo `Z`.** Pydantic emitiría
  `2026-10-28T01:13:51.367000`: la misma fecha, otro texto. Un cliente que
  compare cadenas o parsee con un formato estricto lo notaría.
- **Los errores de validación responden 400, no el 422 de FastAPI.** El 422 es
  más correcto en el vocabulario HTTP, pero este servicio siempre respondió 400
  y el objetivo es que ningún cliente note que cambió de lenguaje.

### Lo que sí cambia

- **Swagger UI nativo en `/docs`** y contrato en `/openapi.json`, generados de
  los mismos modelos Pydantic que validan la petición. No puede haber
  desincronización porque no hay dos fuentes.
- **`/health` reporta `checks.messageBroker`**, que faltaba. Se reporta como
  información del entorno y **no** altera el estado global: este servicio no usa
  el broker, y declararlo DOWN por una dependencia que no utiliza lo retiraría
  del balanceador sin motivo.
- **Pruebas unitarias y de contrato.** La versión en Node solo tenía pruebas de
  validación HTTP. Ahora hay 50 pruebas con 80 % de cobertura y un umbral del
  70 % que rompe el comando.
- **Orden de comprobaciones en el borrado.** Antes se consultaba a
  employees-service y después se intentaba borrar, de modo que un id inexistente
  con el servicio de empleados caído devolvía 503 en lugar de 404. Ahora se
  comprueba la existencia primero.

### Migraciones: Alembic

El ADR-002 exige migraciones versionadas y no generación automática del esquema
desde el modelo. Alembic es el equivalente de Flyway (Java) y Prisma Migrate
(Node), así que la decisión se mantiene aunque cambie el lenguaje.

La revisión inicial es **idempotente** (`IF NOT EXISTS` en todo). El motivo: una
base de datos creada por Prisma tiene la tabla pero no la tabla de versiones de
Alembic; al ejecutar `alembic upgrade head` sobre ella, la revisión corre y no
debe fallar ni duplicar nada. Sin eso, migrar un entorno en funcionamiento
exigiría intervención manual, y una migración que necesita que alguien la
acompañe no sirve para un despliegue automatizado.

### Métricas con los nombres del ecosistema

El servicio publica `http_requests_total` y `http_request_duration_seconds` con
`prometheus-client`, que son los nombres que usan los servicios Node, en lugar de
los que generaría una librería de instrumentación automática de FastAPI. Si
publicara otros, el dashboard de Grafana necesitaría un panel por lenguaje.

## Consecuencias

### Positivas

- La diversidad tecnológica pasa a tres lenguajes, y la traza distribuida lo
  demuestra de verdad: una petición del onboarding produce **48 spans en 7
  servicios con `['Java', 'Node', 'Python']`** en una sola cascada de Zipkin. Es
  la prueba de que W3C Trace Context funciona entre lenguajes, no una afirmación
  del README.
- El contrato OpenAPI ya no puede desincronizarse del código.
- El servicio pasa de 0 pruebas de lógica a 50, con cobertura medida y umbral
  aplicado.
- La migración no requirió tocar `docker-compose.yml` salvo el `SetPath` del
  gateway, ni el `.env`, ni la suite BDD, ni los demás servicios.

### Negativas

- **Un lenguaje más que mantener.** Tres cadenas de herramientas (Maven, npm,
  pip) y tres formas de ejecutar pruebas. Es el coste que la rúbrica pide asumir;
  en un sistema real, la diversidad se justifica por las necesidades de cada
  servicio y no por sí misma.
- **El equipo pierde la familiaridad** que tenía con este servicio. Se mitiga
  conservando la misma estructura de carpetas (`repository`, `service`, `router`,
  `clients`, `observability`) que los otros seis.
- **La URL de la base de datos se traduce en el arranque.** docker-compose
  entrega `postgresql://`, que es lo que entendía Prisma, y SQLAlchemy necesita
  `postgresql+psycopg://`. Se adapta en `config.py` para no tocar el Compose, a
  costa de un detalle que hay que conocer.
- **El esquema queda con nombres que no son idiomáticos en Python.** Un
  desarrollador que llegue nuevo verá `createdAt` en lugar de `created_at` y
  tendrá que leer el ADR para entender por qué.

## Alternativas consideradas

- **Migrar `notifications-service` a Python en lugar de departments.** Tiene más
  lógica (consume seis eventos, deduplica por `eventId`, sanea tokens) y su
  reescritura arriesgaría la idempotencia de los consumidores. Queda asignada a
  Go en el plan, donde su naturaleza de consumidor reactivo encaja mejor.
- **Añadir un servicio nuevo en Python en lugar de reescribir uno.** Cumpliría la
  rúbrica con riesgo cero, pero sería un servicio sin razón de negocio: código
  que existe para satisfacer una tabla. Reescribir uno real obliga a resolver el
  problema de verdad —preservar un contrato entre lenguajes— que es lo que el
  ejercicio enseña.
- **Reescribir en Python pero con snake_case y un esquema nuevo**, migrando los
  datos. Más limpio en el código, pero convierte una reescritura de servicio en
  una migración de datos con ventana de indisponibilidad, por un beneficio
  puramente estético.
- **Mantener el documento OpenAPI a mano también en Python.** Se descartó por ser
  exactamente el defecto que la auditoría encontró.
