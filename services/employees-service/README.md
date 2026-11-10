# Servicio de empleados (employees-service)

API REST con Java 21, Spring Boot 3.4, JPA, PostgreSQL y Spring AMQP (RabbitMQ).
Registra, actualiza y retira empleados, verifica duplicados, valida existencia de departamentos y actúa como **productor de eventos de dominio** hacia el bus de mensajería asíncrona.

---

## Acceso

URL pública: `http://localhost:8080`, mediante el API Gateway. El microservicio expone sus puertos internamente a la red de Docker (`microservices-network`).

| Método | Ruta pública | Resultado principal |
|---|---|---|
| GET | `/empleados/health` | 200: salud del servicio |
| GET | `/empleados/circuit-breaker` | 200: estado y contadores del circuito |
| POST | `/empleados` | 201: empleado registrado + emite `empleado.creado` |
| GET | `/empleados` | 200: listado de empleados; admite filtros y paginación |
| GET | `/empleados/auditoria?desde=YYYY-MM-DD&hasta=YYYY-MM-DD` | 200: retirados en un rango; admite paginación |
| GET | `/empleados/{id}` | 200 o 404: consulta por identificador |
| PUT | `/empleados/{id}` | 200: empleado actualizado + emite `empleado.actualizado` |
| DELETE | `/empleados/{id}` | 200: retiro lógico (estado `RETIRADO`) + emite `empleado.retirado` |

Los listados aceptan `page`, `limit` (el alias histórico `size` también se
mantiene), `sortBy` y `order=ASC|DESC`. Los valores predeterminados son
`page=1`, `limit=10`, `sortBy=id` y `order=ASC`; el límite máximo es 100.
Los campos ordenables son `id`, `employeeNumber`, `nombre`, `apellido`, `email`,
`cargo`, `area`, `departamentoId`, `fechaIngreso`, `fechaRetiro` y `status`.
Cuando se envía alguno de esos parámetros la respuesta usa
`{data,meta:{total,page,limit,totalPages}}`; sin parámetros se conserva el
array histórico. Parámetros inválidos responden 400. Una página fuera de rango
devuelve `data: []` y `totalPages` es 0 cuando no hay resultados.

---

## Productor de Eventos (RabbitMQ)

El microservicio actúa exclusivamente como **productor** de eventos de dominio. Desconoce por completo a los servicios consumidores (`profiles-service`, `notifications-service`, `vacations-service`).

### Topología de Publicación

```text
employees-service
        │
        ▼ (Fanout - routing key "")
   [hr.events] (Exchange durable)
        │
        ├──► profiles.queue
        ├──► notifications.queue
        └──► vacations.queue
```

* **Exchange:** `hr.events` (tipo `fanout`, durable = true).
* **Routing Key:** vacía (`""`), dado que el exchange fanout distribuye a todas las colas suscritas.
* **Colas:** No se declaran colas de consumidores en este servicio; pertenecen a la infraestructura central.

### Eventos Publicados

| Evento | Cuándo se emite | Contrato |
|---|---|---|
| `empleado.creado` | Tras persistir exitosamente un nuevo empleado (POST /empleados). | `shared-events/schemas/empleado.schema.json` |
| `empleado.actualizado` | Tras actualizar los datos de un empleado (PUT /empleados/{id}). | `shared-events/schemas/empleado.schema.json` |
| `empleado.retirado` | Tras realizar el retiro lógico cambiando el estado a `RETIRADO` (DELETE /empleados/{id}). | `shared-events/schemas/empleado.schema.json` |

### Estructura Canónica del Envelope

Todos los mensajes se emiten utilizando el sobre estándar definido en el proyecto:

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-27T22:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "E001",
    "nombre": "Ana",
    "apellido": "Pérez",
    "email": "ana.perez@example.com",
    "departamentoId": "IT",
    "cargo": "Desarrolladora",
    "fechaIngreso": "2026-09-22",
    "fechaRetiro": null
  }
}
```

* `id`: UUID v4 único generado por cada evento (nunca es el ID del empleado).
* `occurredAt`: Marca temporal ISO-8601 en formato UTC (`Instant.now()`).
* `producer`: Identificador del emisor (`employees-service`).
* `data.fechaRetiro`: Contiene la fecha (`yyyy-MM-dd`) cuando el estado es `RETIRADO`, o `null` si está activo.

---

## Transaccionalidad: outbox de eventos

Los eventos **no se publican directamente** en RabbitMQ. Se escriben en la tabla
`outbox_evento`, en la misma transacción que el cambio de negocio, y un
publicador periódico la vacía contra el broker. El razonamiento completo y las
alternativas están en
[ADR-014](../../docs/decisions/ADR-014-outbox-transaccional.md).

### Qué problema resuelve

Antes había dos operaciones contra dos sistemas sin nada que las atara: un
`repository.save()` y, después, un `convertAndSend` envuelto en un `try/catch`
que solo registraba el fallo. Con el broker caído el empleado quedaba creado y
`empleado.creado` no llegaba nunca, así que **no se creaba su cuenta, ni su
perfil, ni su saldo de vacaciones** — y nadie lo reintentaba. El síntoma aparecía
días después, con el empleado pidiendo acceso, y la reparación era manual.

Ahora la tabla del outbox vive en la **misma base de datos** que los empleados,
así que basta una transacción local: o se confirman el empleado y su evento, o no
se confirma ninguno. No hace falta una transacción distribuida.

| Pieza | Qué hace |
|---|---|
| `outbox_evento` (migración `V4`) | Guarda el sobre serializado; `publicado_en` nulo mientras espera |
| `EmployeeEventPublisher` | Lo escribe dentro de la transacción de quien llama |
| `PublicadorDeOutbox` | `@Scheduled` cada 5 s: lee lo pendiente, publica y marca |

Garantiza entrega **al menos una vez**. No se persigue exactamente una vez porque
no hace falta: los consumidores descartan los repetidos por `eventId` (ADR-007).

### Cómo verlo funcionar

```bash
docker compose stop rabbitmq
```

```bash
curl -s -o /dev/null -w "%{http_code}
" -X POST http://localhost:8080/empleados   -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"   -d '{"id":"EOUT1","nombre":"Sofia","apellido":"Ramirez","email":"sofia@empresa.com","numeroEmpleado":"EMP-OUT-001","cargo":"QA","area":"TI","departamentoId":"D001","fechaIngreso":"2026-06-01"}'
```

Responde **201**: el alta ya no depende del broker. Y el evento está esperando:

```bash
docker compose exec database-empleados psql -U postgres -d gestion_empleados -c "SELECT tipo, agregado_id, intentos, publicado_en IS NULL AS pendiente, left(ultimo_error,40) FROM outbox_evento ORDER BY creado_en DESC LIMIT 5;"
```

```
      tipo       | agregado_id | intentos | pendiente |              left
-----------------+-------------+----------+-----------+--------------------------------
 empleado.creado | EOUT1       |        1 | t         | java.net.UnknownHostException: rabbitmq
```

Al devolver el broker, el evento sale solo y la cadena se completa:

```bash
docker compose start rabbitmq
```

```bash
curl -s http://localhost:8080/perfiles/EOUT1 -H "Authorization: Bearer $TOKEN"
```

El perfil existe: `empleado.creado` llegó a su consumidor con minutos de retraso
y sin que nadie interviniera. Antes ese evento se habría perdido para siempre.

### Lo que no cubre

- **Una sola instancia.** Dos réplicas publicarían las mismas filas; los
  consumidores lo absorben por idempotencia, pero el trabajo se duplica. Se
  resolvería con `SELECT ... FOR UPDATE SKIP LOCKED`.
- **La tabla no se purga.** Los eventos publicados se conservan como auditoría;
  hace falta un borrado por antigüedad.
- **Solo está aquí.** `auth-service` y `vacations-service` siguen publicando
  directamente: sus eventos aún se pueden perder.

---

## Variables de Entorno

| Variable | Valor predeterminado (Local) | Valor en Docker Compose |
|---|---|---|
| `SERVER_PORT` | `8080` | `8080` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/gestion_empleados` | `jdbc:postgresql://database-empleados:5432/gestion_empleados` |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | `postgres` |
| `DEPARTMENTS_SERVICE_URL` | `http://localhost:8081` | `http://departments-service:8081` |
| `RABBITMQ_HOST` | `localhost` | `rabbitmq` |
| `RABBITMQ_PORT` | `5672` | `5672` |
| `RABBITMQ_USER` | `admin` | `admin` |
| `RABBITMQ_PASSWORD` | `admin` | `admin` |

---

## Flujo de Registro y Resiliencia

```text
Validación de campos (@Valid)
    → Verificación de duplicados (email, número e ID)
    → DepartmentClient (Resilience4j Circuit Breaker)
        → DepartmentHttpClient (HTTP con reintentos exponenciales)
    → Guardar en PostgreSQL (estado ACTIVO)
    → Publicar evento 'empleado.creado' en exchange 'hr.events'
```

---

## Pruebas Automatizadas

Desde el directorio `services/employees-service`:

```bash
./mvnw test
```

La suite cuenta con **pruebas automatizadas**:
* `EmployeeEndpointsTest` (18 pruebas): Cobertura completa de endpoints REST, validaciones, códigos de error HTTP (400, 404, 503) y Circuit Breaker.
* `EmployeeEventPublisherTest` (4 pruebas): Verificación de generación de `empleado.creado`, `empleado.actualizado`, `empleado.retirado`, formato estricto del Envelope (UUID, ISO-8601, productor) y garantía de no publicación ante fallos de validación/rollback.
* `DepartmentCircuitBreakerTest` (4 pruebas): Comportamiento de estados del circuito (CLOSED, OPEN, HALF_OPEN) con servidor mock.
* `DepartmentClientRetryTest` (1 prueba): Validación de reintentos exponenciales ante fallos de red.
* `DepartmentCacheTest` y `DepartmentCacheFallbackTest`: Cobertura completa del caso alternativo de contingencia mediante caché en memoria ante indisponibilidad de departamentos.

---

## Caso alternativo — Caché de contingencia

Como mejora para la resiliencia, el sistema cuenta con un caché temporal de información de departamentos.

* **Propósito:** Mantener temporalmente la capacidad de validar departamentos durante una interrupción de la comunicación con `departments-service`.
* **Ubicación:** Integrado en el `DepartmentClient` dentro de `employees-service`.
* **TTL:** 30 minutos (configurable vía `departments.cache.ttl-minutes` / `DEPARTMENTS_CACHE_TTL_MINUTES`).
* **Fuente de verdad:** El servicio real y su base de datos. El caché se alimenta estrictamente de las respuestas exitosas de `departments-service`.
* **Cuándo se utiliza:** Únicamente como fallback tras agotar los reintentos HTTP o cuando el Circuit Breaker está abierto (`OPEN`). 
* **Cuándo NO se utiliza:** Nunca se utiliza como primera opción frente al servicio disponible. Además, un `404` (departamento inexistente) del servicio real no usa el caché porque constituye una comunicación exitosa.
* **Comportamiento ante cache miss:** Devuelve `503 Service Unavailable`, manteniendo el comportamiento original.
* **Comportamiento ante expiración:** Si la entrada superó su TTL, no se utiliza y devuelve `503`.
* **Recuperación del servicio:** Cuando el servicio real se recupera, las consultas vuelven a pasar por él (estado `HALF_OPEN` -> `CLOSED`) y el caché se actualiza.
* **Relación con Circuit Breaker:** Funciona de forma conjunta y posterior al CB. El CB sigue protegiendo la red, y el caché actúa en memoria cuando el CB determina la indisponibilidad.
* **Consistencia eventual:** Durante la ventana de caída, el servicio puede permitir registros si el caché está válido pero el departamento fue borrado de la base de datos de origen en ese intermedio. Esta limitación de consistencia eventual es asumida a favor de la disponibilidad.
