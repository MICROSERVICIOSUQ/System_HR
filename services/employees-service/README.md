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
| GET | `/empleados` | 200: listado de empleados |
| GET | `/empleados/{id}` | 200 o 404: consulta por identificador |
| PUT | `/empleados/{id}` | 200: empleado actualizado + emite `empleado.actualizado` |
| DELETE | `/empleados/{id}` | 200: retiro lógico (estado `RETIRADO`) + emite `empleado.retirado` |

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

## Decisión de Arquitectura: Transaccionalidad y Consistencia

### ¿Por qué no Outbox Pattern en esta etapa?
El patrón *Transactional Outbox* requiere una tabla adicional en base de datos (`outbox_events`), coordinación transaccional local y un proceso en segundo plano (poller periódico o Change Data Capture como Debezium) para publicar en RabbitMQ. 

Para esta fase del Reto 4, se adopta la **estrategia de publicación confiable post-persistencia**:
1. La operación de base de datos se ejecuta primero (validaciones, asignación de estado y `repository.save()`).
2. Si la base de datos falla o se produce un rollback, **nunca se publica el evento en RabbitMQ**, evitando inconsistencias por eventos fantasmas.
3. Tras la persistencia exitosa, se emite el evento al exchange `hr.events`.
4. Si ocurre un fallo en RabbitMQ, el error se captura y se registra con log detallado a nivel `ERROR` (incluyendo tipo, id de evento, id de empleado y causa raíz) para alerta inmediata a observabilidad, manteniendo intacta la respuesta HTTP de persistencia (`201` o `200`) para no degradar el contrato con clientes HTTP.

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
