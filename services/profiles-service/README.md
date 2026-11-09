# Servicio de Perfiles (profiles-service)

Microservicio desarrollado con **Node.js 22**, **TypeScript**, **Express**, **Prisma** y **PostgreSQL**.

Implementa un **Read Model CQRS**: proyección desnormalizada de empleados
construida principalmente mediante eventos de RabbitMQ. También expone operaciones
HTTP de administración sobre la proyección; nunca consulta a `employees-service`.

---

## Arquitectura CQRS — Read Model

```text
[employees-service]  (Productor)
        │
        ▼ (Fanout)
   [hr.events] (Exchange)
        │
        ├──► [notifications.queue]  → notifications-service
        │
        ├──► [profiles.queue]       ← ★ ESTE SERVICIO
        │            │
        │            ▼ (amqplib — sin modificar topología)
        │    [profiles-service]
        │            │
        │            ▼ (Prisma ORM)
        │    [PostgreSQL: database-perfiles]
        │            └── Tabla: EmployeeProfile
        │                  ├── employeeId  (UNIQUE)
        │                  └── lastEventId (UNIQUE — idempotencia)
        │
        └──► [vacations.queue]
```

### Principios CQRS aplicados

| Aspecto | Detalle |
|---|---|
| **Escritura** | Eventos de RabbitMQ (`profiles.queue`) y CRUD HTTP administrativo |
| **Lectura** | API REST sobre PostgreSQL — sin llamar a otros servicios |
| **Consistencia** | Eventual — el estado se construye proyectando eventos |
| **Sin acoplamiento** | Cero llamadas HTTP a `employees-service` |

---

## Modelo de Datos — `EmployeeProfile`

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | UUID | Clave primaria interna generada automáticamente |
| `employeeId` | String (UNIQUE) | ID del empleado proveniente del evento |
| `nombre` | String | Nombre del empleado |
| `apellido` | String | Apellido del empleado |
| `email` | String | Correo electrónico |
| `cargo` | String | Cargo o posición |
| `departamentoId` | String | ID del departamento asociado |
| `fechaIngreso` | DateTime | Fecha de ingreso a la empresa |
| `fechaRetiro` | DateTime? | Fecha de retiro (null mientras ACTIVO) |
| `estado` | String | `ACTIVO` \| `RETIRADO` |
| `lastEventId` | String (UNIQUE) | UUID del último evento aplicado (idempotencia) |
| `updatedAt` | DateTime | Actualizado automáticamente por Prisma en cada operación |

---

## Eventos Consumidos

| Evento | Acción | Descripción |
|---|---|---|
| `empleado.creado` | `CREATE` | Crea el perfil con estado `ACTIVO` |
| `empleado.actualizado` | `UPDATE` | Actualiza nombre, apellido, email, cargo y departamentoId |
| `empleado.retirado` | `UPDATE` | Marca estado = `RETIRADO` y guarda `fechaRetiro`. **No elimina el registro.** |

Cualquier otro tipo de evento se descarta con `ACK` sin modificar la base de datos.

---

## Garantía de Idempotencia

RabbitMQ garantiza entrega *at-least-once*, lo que puede ocasionar reentregas. Para garantizar que ningún evento se aplique dos veces:

1. **`lastEventId` UNIQUE en BD:** cada evento tiene un UUID único (`envelope.id`).
2. **Comprobación previa:** antes de cualquier escritura, se consulta si `lastEventId` ya existe:
   - **Existe → `[DUPLICADO]`:** log de advertencia + `ACK` inmediato sin modificar la BD.
   - **No existe → procesamiento normal:** aplicar actualización + guardar `lastEventId` + `ACK`.
3. **Race condition protegida:** si dos workers procesan el mismo evento simultáneamente, el `UNIQUE constraint` de la BD lanza `P2002`, que es capturado y tratado como duplicado.

---

## Endpoints de la API REST

El servicio usa `expose: 8083`, así que no es alcanzable desde el host: todas
las peticiones entran por el API Gateway, que enruta `/perfiles` y `/profiles` y
valida el JWT antes de reenviarlas (ADR-008). Sobre `PUT`/`PATCH` aplica además
la regla de propiedad: un `USER` edita su propio perfil y recibe **403** si
intenta el de otro.

`/perfiles` es la ruta canónica; `/profiles` se conserva como alias, igual que
`vacations-service` sirve `/vacaciones` y `/vacations`.

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/health` | Estado propio y de sus dependencias; **503** si alguna falla |
| `GET` | `/metrics` | Métricas en formato Prometheus |
| `GET` | `/docs` | Swagger UI |
| `GET` | `/openapi.json` | Contrato OpenAPI 3.0.3 |
| `POST` | `/perfiles` | Crea una proyección de perfil (`Idempotency-Key` opcional) |
| `GET` | `/perfiles` | Todos los perfiles (array) |
| `GET` | `/perfiles?estado=ACTIVO` | Solo perfiles de empleados activos |
| `GET` | `/perfiles?archivado=false` | Solo los perfiles vigentes |
| `GET` | `/perfiles?page=1&limit=10&sortBy=fechaCreacion&order=DESC` | Página de perfiles; `limit` máximo 100 |
| `GET` | `/perfiles/{empleadoId}` | Perfil individual (`200` o `404`) |
| `PUT/PATCH` | `/perfiles/{empleadoId}` | Actualiza datos y valida transiciones de estado |
| `DELETE` | `/perfiles/{empleadoId}` | Retira y archiva: nunca borra, responde **200** con el perfil |

Las actualizaciones aceptan `Idempotency-Key`. Repetir una operación con la misma
clave devuelve la proyección ya persistida sin aplicarla de nuevo. Un perfil
`RETIRADO` no puede volver a `ACTIVO`. Cuando una operación HTTP cambia el estado
a `RETIRADO`, se publica un evento `empleado.retirado` en el exchange existente
`hr.events`; el consumidor conserva `fechaRetiro` y nunca elimina el registro.

La paginación acepta `page`, `limit` (o el alias histórico `size`), `sortBy` y
`order=ASC|DESC`. Los valores predeterminados son `page=1`, `limit=10`,
`sortBy=updatedAt` y `order=DESC`. Se permiten `empleadoId`, `nombre`,
`apellido`, `email`, `cargo`, `departamentoId`, `fechaIngreso`, `estado`,
`fechaRetiro`, `fechaCreacion`, `archivado` y `updatedAt`: la lista blanca es
`CAMPOS_ORDENABLES` del repositorio, de donde la leen también el controlador y
el contrato OpenAPI, para que no puedan discrepar. Las respuestas paginadas usan
`{data, meta:{total,page,limit,totalPages}}`; sin parámetros se conserva el
array histórico. Parámetros inválidos responden 400, una página fuera de rango
devuelve `data: []` y `totalPages` es 0 si no hay resultados.

---

## Los cinco campos canónicos del perfil

`telefono`, `direccion`, `ciudad`, `biografia` y `fechaCreacion`.

Los cuatro primeros son **datos del perfil, no del empleado**:
`employees-service` no los conoce ni los publica en sus eventos. Por eso son
opcionales y los rellena el propio empleado con `PUT /perfiles/{empleadoId}`, que
es justo la operación que la regla de propiedad le autoriza sobre su recurso.
Hacerlos obligatorios sería imposible: el perfil nace de un evento que no los
trae.

`fechaCreacion` es cuándo se creó el perfil en el read model, y es
deliberadamente distinta de `fechaIngreso`, que es cuándo entró el empleado a la
empresa. Pueden diferir en meses si el evento se procesó tarde, y confundirlas
haría mentir a un listado por antigüedad.

### `empleadoId`, con `employeeId` como alias

La columna se llamaba `employeeId` y era el único campo del sistema en inglés.
Se renombró al nombre canónico, pero la respuesta sigue incluyendo los dos
nombres apuntando al mismo valor: el alias está marcado `@deprecated` en el
código y en el contrato. El razonamiento está en
[ADR-013](../../docs/decisions/ADR-013-renombrar-campos-publicados.md).

### La marca de archivado

Un perfil retirado no se borra —el historial se conserva para auditoría— pero sí
se archiva, para que las consultas corrientes no lo arrastren:

```bash
curl -s -H "Authorization: Bearer $TOKEN"   "http://localhost:8080/perfiles?archivado=false"
```

`archivado` **no es un estado más, es otra dimensión**: `estado` dice qué le pasó
al empleado y `archivado` si el perfil entra en las consultas corrientes. Hoy
coinciden, pero un perfil podría archivarse por otro motivo sin estar retirado, y
entonces las dos consultas dejarían de ser la misma.

Se escribe en la **misma operación** que el retiro. Si fueran dos escrituras, un
fallo entre ellas dejaría un perfil retirado sin archivar y nada lo reintentaría:
el consumidor es idempotente y ya habría registrado el `eventId`.

Un valor que no sea `true` o `false` responde **400** en lugar de ignorarse: un
filtro mal escrito que se descarta en silencio devuelve una lista que parece
correcta y no lo es. Y omitir el parámetro no filtra, que no es lo mismo que
`archivado=false`: si lo fuera, `GET /perfiles` dejaría de devolver los
archivados y cambiaría el comportamiento de un endpoint ya publicado.

---

## Documentación de la API

| Qué | Dónde |
|---|---|
| Swagger UI | `/docs`, `/perfiles/docs` |
| Contrato | `/openapi.json`, `/perfiles/openapi.json` |
| Agregado en el borde | `http://localhost:8080/swagger-ui.html` → *4. profiles-service* |
| Contrato por el gateway | `http://localhost:8080/v3/api-docs/perfiles` |

Este servicio era el único de los siete sin documentación OpenAPI, y la auditoría
lo marcó. El contrato está escrito a mano en `src/docs/openapi.ts` porque la
validación de este servicio también lo está —no hay librería de esquemas de la
que generarlo— pero **no puede desincronizarse del código**, que era el defecto
real que la auditoría encontró en el antiguo departments-service:

- el enum de `sortBy` y los campos del esquema `Perfil` se **importan** del
  código, no se copian;
- `src/tests/openapi.test.ts` comprueba que las rutas documentadas son
  exactamente las que registra el router y que las propiedades de `Perfil` son
  exactamente las que devuelve `presentarPerfil`.

Si alguien añade una ruta, un campo o un valor de ordenación y no toca el
contrato, la suite falla.

---

## Pruebas

```bash
npm test
```

```
49 passed
all files | 95.40 % líneas | 80.09 % ramas
Umbral del 70 % aplicado: el comando falla si la cobertura baja
```

No necesitan base de datos, red ni broker: el repositorio y el publicador se
sustituyen por dobles en memoria (ADR-010). `src/tests/entorno-pruebas.ts` se
carga con `--import` y pone una `DATABASE_URL` de marcador antes de que se evalúe
cualquier módulo, porque `src/config/db.ts` falla de inmediato sin ella —lo cual
es correcto en producción, pero impedía cargar las pruebas—.

Las cuatro suites:

| Archivo | Qué cubre |
|---|---|
| `profile.service.test.ts` | Reglas de negocio, idempotencia por `eventId`, archivado, consumo de los tres eventos |
| `profile.mapper.test.ts` | Forma de la respuesta y el alias `employeeId` |
| `openapi.test.ts` | Consistencia entre el contrato y el código |
| `profile.validation.test.ts` | Validación HTTP a través del servidor real |

El umbral **rompe el comando**, igual que el gate de JaCoCo en
`employees-service`: un umbral que no falla el build es una sugerencia. Quedan
fuera de la medición el repositorio, la mensajería, la observabilidad y el
arranque, con el mismo criterio que en los otros servicios: se mide la lógica, y
la observabilidad se verifica de extremo a extremo en Zipkin, Prometheus y
Grafana.

---

## Variables de Entorno

| Variable | Descripción | Valor Docker Compose | Valor Local |
|---|---|---|---|
| `PORT` | Puerto HTTP de Express | `8083` | `8083` |
| `DATABASE_URL` | Cadena de conexión PostgreSQL | `postgresql://postgres:postgres@database-perfiles:5432/gestion_perfiles` | `postgresql://postgres:postgres@localhost:5435/gestion_perfiles` |
| `RABBITMQ_HOST` | Host del broker | `rabbitmq` | `localhost` |
| `RABBITMQ_PORT` | Puerto AMQP | `5672` | `5672` |
| `RABBITMQ_USER` | Usuario de RabbitMQ | `admin` | `admin` |
| `RABBITMQ_PASSWORD` | Contraseña de RabbitMQ | `admin` | `admin` |

---

## Cómo Probar

### 1. Levantar el stack completo

```powershell
docker compose up -d --build profiles-service
```

### 2. Verificar salud del servicio

```powershell
docker compose exec profiles-service curl -s http://localhost:8083/health
```

```json
{ "status": "UP", "service": "profiles-service",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

Devuelve **503** si una dependencia falla. Antes respondía
`{"status":"ok","service":"profiles"}` sin comprobar nada: con PostgreSQL caído
el contenedor seguía declarándose sano y Docker lo mantenía en servicio.

### 3. Publicar un evento `empleado.creado` desde el panel de RabbitMQ

1. Accede a **http://localhost:15672** con `admin` / `admin`.
2. Navega a **Exchanges** → **hr.events** → **Publish message**.
3. Deja `Routing key` vacía (exchange fanout).
4. En `Payload` pega:

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-28T20:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "EMP-001",
    "nombre": "Ana",
    "apellido": "López",
    "email": "ana.lopez@empresa.com",
    "departamentoId": "DEPT-IT",
    "cargo": "Backend Developer",
    "fechaIngreso": "2026-09-28",
    "fechaRetiro": null
  }
}
```

5. Observa los logs:

```powershell
docker compose logs -f profiles-service
```

Verás:
```text
[PROFILE] Mensaje recibido | UUID: a1b2c3d4-... | Evento: empleado.creado | Empleado: EMP-001
[PROFILE] [CREADO] Perfil creado | UUID: a1b2c3d4-... | Empleado: EMP-001 | Nombre: Ana López | Email: ana.lopez@empresa.com
```

### 4. Verificar idempotencia

Publica el mismo mensaje dos veces. En el segundo envío verás:

```text
[PROFILE] [DUPLICADO] Evento ya procesado anteriormente | UUID: a1b2c3d4-... | Tipo: empleado.creado | Empleado: EMP-001
```

### 5. Consultar la API

```powershell
# Todos los perfiles
docker compose exec profiles-service curl -s http://localhost:8083/profiles | jq

# Solo activos
docker compose exec profiles-service curl -s "http://localhost:8083/profiles?estado=ACTIVO" | jq

# Por empleado
docker compose exec profiles-service curl -s http://localhost:8083/profiles/EMP-001 | jq
```

### 6. Publicar `empleado.actualizado`

```json
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "type": "empleado.actualizado",
  "version": "1.0",
  "occurredAt": "2026-09-28T21:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "EMP-001",
    "nombre": "Ana María",
    "apellido": "López Ruiz",
    "email": "anamaria.lopezruiz@empresa.com",
    "departamentoId": "DEPT-BACKEND",
    "cargo": "Senior Backend Developer"
  }
}
```

### 7. Publicar `empleado.retirado`

```json
{
  "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "type": "empleado.retirado",
  "version": "1.0",
  "occurredAt": "2026-09-28T22:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "EMP-001",
    "fechaRetiro": "2026-09-28T22:00:00.000Z"
  }
}
```

Resultado esperado al consultar:
```json
{
  "estado": "RETIRADO",
  "fechaRetiro": "2026-09-28T22:00:00.000Z",
  ...
}
```

---

## Formato de Logs

Los registros son JSON por stdout, con los cinco campos que exige la auditoría
—`timestamp`, `level`, `service`, `context` y `traceId`— más `spanId` y
`message`. Promtail los recoge y Loki permite saltar de un ERROR a la traza
completa en Zipkin por su `traceId`.

```json
{"timestamp":"2026-11-09T18:11:46.185Z","level":"INFO","service":"profiles-service",
 "message":"[PROFILE] [CREADO] Perfil creado","traceId":"26b4b7d1a6d0b9af074f45763b73661c",
 "spanId":"2d0dca16a52b967c",
 "context":{"empleadoId":"ELOG2","eventId":"a122d0ab-118f-4759-9b0f-07ee2308360c",
            "nombre":"Ana Ruiz","email":"ana.log2@empresa.com"}}
```

Los datos del registro van **dentro de `context`** y no en la raíz. Con cada
campo en la raíz, el documento cambiaba de forma según el mensaje y una consulta
en Loki tenía que saber de antemano cuál lo había producido; agrupados, el
esquema es siempre el mismo y se puede filtrar por `context_empleadoId` sin
conocer el mensaje. `context` se emite incluso vacío, por la misma razón que
`traceId`: un campo ausente obliga a comprobar su existencia en cada consulta.

| Prefijo | Significado |
|---|---|
| `[PROFILE] Mensaje recibido` | Llegó un mensaje desde RabbitMQ |
| `[PROFILE] Evento ignorado` | Tipo de evento no gestionado por este servicio |
| `[PROFILE] [CREADO]` | Perfil nuevo persistido |
| `[PROFILE] [ACTUALIZADO]` | Datos del perfil actualizados |
| `[PROFILE] [RETIRADO]` | Perfil marcado como RETIRADO |
| `[PROFILE] [DUPLICADO]` | Evento ya procesado (idempotencia activa) |
| `[PROFILE] [ERROR]` | Excepción durante el procesamiento |
| `[RABBITMQ] Conectando` | Intentando conexión con el broker |
| `[RABBITMQ] Conectado exitosamente` | Conexión y consumo activos |
