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

El servicio está **expuesto internamente** a la red Docker (`expose: 8083`). No está registrado en el API Gateway.

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/health` | Estado del servicio `{"status":"ok","service":"profiles"}` |
| `POST` | `/profiles` | Crea una proyección de perfil (`Idempotency-Key` opcional) |
| `GET` | `/profiles` | Todos los perfiles (sin filtro) |
| `GET` | `/profiles?estado=ACTIVO` | Solo perfiles activos |
| `GET` | `/profiles?estado=RETIRADO` | Solo perfiles retirados |
| `GET` | `/profiles?page=1&limit=10&sortBy=updatedAt&order=DESC` | Página de perfiles; `limit` máximo 100 |
| `GET` | `/profiles/:employeeId` | Perfil individual por ID de empleado (`200` o `404`) |
| `PUT/PATCH` | `/profiles/:employeeId` | Actualiza datos y valida transiciones de estado |
| `DELETE` | `/profiles/:employeeId` | Baja lógica: conserva el perfil y lo marca `RETIRADO` |

Las actualizaciones aceptan `Idempotency-Key`. Repetir una operación con la misma
clave devuelve la proyección ya persistida sin aplicarla de nuevo. Un perfil
`RETIRADO` no puede volver a `ACTIVO`. Cuando una operación HTTP cambia el estado
a `RETIRADO`, se publica un evento `empleado.retirado` en el exchange existente
`hr.events`; el consumidor conserva `fechaRetiro` y nunca elimina el registro.

La paginación acepta `page`, `limit` (o el alias histórico `size`), `sortBy` y
`order=ASC|DESC`. Los valores predeterminados son `page=1`, `limit=10`,
`sortBy=updatedAt` y `order=DESC`. Se permiten `employeeId`, `nombre`,
`apellido`, `email`, `cargo`, `departamentoId`, `fechaIngreso`, `estado`,
`fechaRetiro`, `createdAt` y `updatedAt`. Las respuestas paginadas usan
`{data, meta:{total,page,limit,totalPages}}`; sin parámetros se conserva el
array histórico. Parámetros inválidos responden 400, una página fuera de rango
devuelve `data: []` y `totalPages` es 0 si no hay resultados.

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
# {"status":"ok","service":"profiles"}
```

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
