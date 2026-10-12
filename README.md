# Sistema de Recursos Humanos — System_HR (Reto 3 y Reto 4)

Sistema empresarial de gestión de empleados y departamentos basado en **Arquitectura de Microservicios**, combinando comunicación síncrona protegida mediante **Circuit Breaker** (Reto 3) con comunicación asíncrona desacoplada mediante **RabbitMQ y Arquitectura Orientada a Eventos** (Reto 4).

---

# PARTE I — Reto 3: Comunicación Síncrona, API Gateway y Resiliencia

## Arquitectura Síncrona

```text
Cliente HTTP
    │ http://localhost:8080
    ▼
API Gateway
    ├── /empleados y /empleados/** ────────► employees-service
    │                                         │
    │                                         ├── PostgreSQL empleados
    │                                         │
    │                                         └── Circuit Breaker + reintentos
    │                                                      │
    └── /departamentos y /departamentos/** ───► departments-service
                                                           │
                                                           └── PostgreSQL departamentos
```

Solo el Gateway publica un puerto al host. Los servicios de negocio usan `expose` y las bases de datos no publican puertos. Cada servicio mantiene su propio modelo, reglas y migraciones.

## Servicios y Tecnologías (Núcleo Síncrono)

| Servicio | Tecnología | Puerto interno predeterminado | Acceso desde el host |
|---|---|---|---|
| api-gateway | Java 17, Spring Boot y Spring Cloud Gateway | 8080 | 8080 |
| employees-service | Java 21, Spring Boot, JPA y Resilience4j | 8080 | Mediante Gateway |
| departments-service | Node.js 22, TypeScript, Express y Prisma | 8081 | Mediante Gateway |
| database-empleados | PostgreSQL 16 | 5432 | Sin puerto publicado |
| database-departamentos | PostgreSQL 16 | 5432 | Sin puerto publicado |

El Gateway y empleados pueden usar el mismo puerto interno porque se ejecutan en contenedores distintos.

### Elección del Gateway

Se utiliza Spring Cloud Gateway porque es un Gateway de aplicación, permite configurar rutas y añadir código para responsabilidades transversales. En esta entrega enruta solicitudes y controla errores de conexión; permite incorporar autenticación y autorización en retos posteriores. Las reglas de registro y validación permanecen en los servicios.

## URL Base y Rutas Síncronas

URL pública única: `http://localhost:8080`.

| Ruta externa | Destino predeterminado | Uso |
|---|---|---|
| `/health` | Gateway | Salud propia del Gateway |
| `/empleados` y `/empleados/**` | `http://employees-service:8080` | API de empleados |
| `/departamentos` y `/departamentos/**` | `http://departments-service:8081` | API de departamentos |
| `/empleados/health` | employees-service | Salud de empleados |
| `/empleados/circuit-breaker` | employees-service | Estado y contadores del circuito |

El Gateway conserva las rutas de negocio y propaga cuerpos, cabeceras de aplicación y códigos de respuesta. Si no puede comunicarse con el destino o vence el timeout, devuelve `503` con un JSON descriptivo.

Las interfaces Swagger y las especificaciones OpenAPI siguen disponibles dentro de cada servicio, pero sus rutas no están publicadas por el Gateway actual. El `/health` interno de departamentos tampoco se enruta como una ruta pública: Compose lo utiliza dentro del contenedor.

## Circuit Breaker (Resilience4j)

La llamada de empleados a departamentos está protegida por Resilience4j. `DepartmentClient` ejecuta la operación mediante el circuito; `DepartmentHttpClient` realiza la consulta HTTP y sus reintentos. Las validaciones de duplicados y la persistencia quedan fuera del circuito de departamentos.

| Parámetro | Valor |
|---|---|
| Nombre | `departmentsCircuitBreaker` |
| Ventana | Últimas 3 operaciones |
| Mínimo para evaluar | 3 operaciones |
| Umbral de fallos | 100 %: tres operaciones consecutivas fallidas |
| Espera en OPEN | 30 segundos |
| Operaciones de prueba en HALF_OPEN | 1 |
| Timeout de conexión HTTP | 3 segundos |
| Timeout de lectura HTTP | 5 segundos |
| Intentos por operación | 4 en total |
| Esperas entre intentos | 1, 2 y 4 segundos |

Una operación con sus reintentos agotados cuenta como un fallo del circuito. En OPEN se rechaza la consulta sin llamar a departamentos. Después de 30 segundos pasa a HALF_OPEN; si la operación de prueba obtiene una respuesta válida, cierra; si falla por indisponibilidad, vuelve a abrir.

Se eligieron tres fallos para detectar una caída sostenida después de los reintentos, y 30 segundos para permitir la recuperación sin consultar continuamente la dependencia. Una sola operación de prueba limita la carga durante la recuperación.

Un departamento inexistente devuelve un 404 desde departamentos: es una comunicación exitosa. Empleados lo traduce en `400` por regla de negocio, sin contarlo como caída del servicio.

Consulta del estado:

```bash
curl -s http://localhost:8080/empleados/circuit-breaker
```

También se registran las transiciones CLOSED, OPEN y HALF_OPEN en los logs de empleados.

## Estrategia de Fallback

Si departamentos no está disponible o el circuito está abierto, el registro devuelve `503 Service Unavailable`, con el mensaje:

```json
{"message":"El servicio de departamentos no está disponible temporalmente"}
```

Este es el mensaje relevante; la respuesta incluye también `timestamp`, `status`, `error` y `path`.

Se prioriza la consistencia: no se guarda un empleado cuyo departamento no pudo validarse. El usuario debe reintentar después de la recuperación. No se crean registros pendientes y, por tanto, no se necesita reconciliación de esos registros. Las consultas que no dependen de departamentos pueden seguir funcionando si la base de empleados está disponible.

## Prueba Reproducible del Reto 3

Con los servicios saludables y el circuito cerrado, desde la raíz del repositorio:

```bash
python3 tests/reto3_smoke.py
```

El script crea datos con identificadores nuevos, detiene departamentos, comprueba los errores del Gateway y ejecuta ocho intentos de registro. Después restaura departamentos y verifica la recuperación automática. No elimina datos ni volúmenes; deja los registros de prueba creados durante la etapa saludable.

Para guardar la salida como evidencia:

```bash
python3 tests/reto3_smoke.py > reto3-evidencia.jsonl
```

Para demostrar también el rechazo de conexiones directas en un entorno donde esos puertos no estén ocupados por otros programas:

```bash
curl -i --max-time 5 http://localhost:8081/departamentos
curl -i --max-time 5 http://localhost:8082/empleados
```

Ambas conexiones deben rechazarse. El script además verifica que los contenedores reales de negocio no tienen puertos publicados.

## Resultados Verificados del Reto 3

En la ejecución del 22 de septiembre de 2026, realizada con contenedores y volúmenes de prueba aislados:

| Comprobación | Resultado |
|---|---|
| Pruebas automatizadas del Gateway | 6 aprobadas |
| Pruebas automatizadas de empleados | 23 aprobadas |
| Registro con el sistema saludable | 201 |
| Destino caído a través del Gateway | 503 con JSON |
| Primeros tres registros con departamentos caído | 19,04 s; 18,03 s; 12,02 s |
| Registros siguientes con circuito abierto | Aproximadamente 0,010 s |
| Acceso directo a 8081 y 8082 | Conexión rechazada |
| Recuperación automática | OPEN → HALF_OPEN → CLOSED |
| Registro con departamento inexistente tras recuperarse | 400 por departamento inexistente |

---

# PARTE II — Reto 4: Arquitectura Orientada a Eventos y Mensajería Asíncrona

## 1. Descripción y Objetivos del Reto 4

El **Reto 4** incorpora comunicación asíncrona desacoplada mediante **RabbitMQ** bajo el patrón **Event-Driven Architecture (EDA)** y el modelo **CQRS (Read Model)**.

El sistema demuestra y valida los siguientes principios:
* **API Gateway como único punto de entrada:** Control perimetral centralizado.
* **Microservicios independientes:** Separación de dominios con ciclos de vida y despliegues autónomos.
* **Database-per-Service:** Cada microservicio gestiona su propia base PostgreSQL independiente.
* **Comunicación asíncrona Fanout:** Transmisión 1 a N de eventos de dominio a múltiples consumidores simultáneos.
* **Idempotencia en consumidores:** Manejo seguro de reentregas de red mediante llaves de evento (`lastEventId`) e índices únicos.

## 2. Arquitectura General del Reto 4

```text
                         Cliente
                            │
                    http://localhost:8080
                            │
                 API Gateway (Spring Boot)
              ┌─────────────┴─────────────┐
              │                           │
      Employees Service          Departments Service
              │                           │
              │ HTTP                      │
              └───────────────┐           │
                              │
                      RabbitMQ Exchange
                         hr.events
                         (Fanout)
      ┌───────────────┬───────────────┬───────────────┐
      │               │               │
Notifications     Profiles      Vacations
      │               │               │
  PostgreSQL      PostgreSQL     PostgreSQL
```

### Principios Fundamentales
* **Único puerto público:** Solo el API Gateway publica el puerto `8080` hacia el host.
* **Aislamiento entre consumidores:** `notifications-service`, `profiles-service` y `vacations-service` nunca se llaman entre sí; consumen eventos de forma completamente asíncrona.
* **Bases de datos independientes:** Cada consumidor persiste exclusivamente en su propia base de datos PostgreSQL.

## 3. Catálogo Completo de Microservicios

| Servicio | Tecnología | Puerto interno | Base de datos | Rol en Reto 4 |
|---|---|---|---|---|
| api-gateway | Spring Boot | 8080 | No | Enrutamiento perimetral |
| employees-service | Spring Boot | 8080 | PostgreSQL | Productor de eventos de dominio |
| departments-service | Node + Prisma | 8081 | PostgreSQL | Catálogo de departamentos |
| notifications-service | Node + Prisma | 8082 | PostgreSQL | Consumidor asíncrono (notificaciones) |
| profiles-service | Node + Prisma | 8083 | PostgreSQL | Consumidor asíncrono (Read Model CQRS) |
| vacations-service | Node + Prisma | 8084 | PostgreSQL | Consumidor asíncrono (saldo vacaciones) |
| rabbitmq | RabbitMQ Management | 5672 | No | Message Broker AMQP |

> [!NOTE]
> **Solo el puerto 8080 es público** (API Gateway). Los servicios de negocio operan aislados mediante la directiva `expose` en la red interna de Docker (`sistema_rrhh_network`).

## 4. Infraestructura RabbitMQ

### Exchange

| Nombre | Tipo | Durabilidad |
|---|---|---|
| hr.events | Fanout | Durable |

### Colas

| Cola | Consumidor | Enlace (Binding) |
|---|---|---|
| notifications.queue | notifications-service | Vinculada a `hr.events` |
| profiles.queue | profiles-service | Vinculada a `hr.events` |
| vacations.queue | vacations-service | Vinculada a `hr.events` |

> **Funcionamiento Fanout:** El exchange `hr.events` duplica automáticamente cada evento emitido hacia las tres colas suscritas. La *routing key* siempre es vacía (`""`), pues el enrutamiento es de difusión global.

## 5. Catálogo de Eventos

| Evento | Productor | Consumidores | Descripción |
|---|---|---|---|
| empleado.creado | employees-service | notifications, profiles, vacations | Alta de nuevo empleado |
| empleado.actualizado | employees-service | notifications, profiles, vacations | Modificación de datos del empleado |
| empleado.retirado | employees-service | notifications, profiles, vacations | Retiro lógico del empleado |

### Estructura del Event Envelope

Todos los mensajes transmitidos en RabbitMQ viajan bajo el sobre estándar canónico:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-28T20:00:00Z",
  "producer": "employees-service",
  "data": {
    "id": "E001",
    "nombre": "Juan",
    "apellido": "Perez",
    "email": "juan@empresa.com",
    "cargo": "Developer",
    "departamentoId": "D001"
  }
}
```

## 6. Organización del Proyecto

```text
System_HR/
├── api-gateway/
├── services/
│   ├── employees-service/
│   ├── departments-service/
│   ├── notifications-service/
│   ├── profiles-service/
│   └── vacations-service/
├── infrastructure/
│   ├── databases/
│   └── rabbitmq/
├── shared-events/
│   ├── catalog/
│   ├── schemas/
│   └── docs/
├── docs/
│   ├── architecture/
│   ├── decisions/
│   └── evidencias/
├── postman/
├── tests/
├── docker-compose.yml
└── README.md
```

* **`api-gateway/`**: Gateway de entrada basado en Spring Cloud Gateway.
* **`services/`**: Código fuente de los microservicios de dominio (Productores y Consumidores).
* **`infrastructure/`**: Definiciones de Docker Compose para bases PostgreSQL y RabbitMQ.
* **`shared-events/`**: Repositorio de especificaciones, contratos de eventos y JSON Schemas compartidos.
* **`docs/decisions/`**: Registros formales de decisiones de arquitectura (ADR).
* **`postman/`**: Colecciones y entornos de Postman para pruebas de integración de API.
* **`tests/`**: Scripts automatizados de pruebas de humo y validación de resiliencia.

## 7. Cómo Ejecutar el Sistema Completo

### Requisitos previos
* **Docker Desktop** (con soporte para Docker Compose v2 e `include`).

### Comandos principales

```bash
# Iniciar todos los contenedores en segundo plano
docker compose up --build -d

# Verificar estado y healthchecks
docker compose ps

# Detener el sistema conservando los volúmenes de datos
docker compose down
```

### URLs y Accesos Principales

| Recurso | URL | Credenciales |
|---|---|---|
| API Gateway | [http://localhost:8080](http://localhost:8080) | N/A |
| RabbitMQ Management | [http://localhost:15672](http://localhost:15672) | `admin` / `admin` |
| Health Gateway | [http://localhost:8080/health](http://localhost:8080/health) | N/A |

## 8. Cómo Probar el Sistema (Guía Paso a Paso)

### Prueba 1 — Crear un Departamento
```bash
curl -i -X POST http://localhost:8080/departamentos \
  -H "Content-Type: application/json" \
  -d '{"nombre": "Tecnología", "codigo": "DEP-TEC"}'
```
* **Esperado:** Código HTTP `201 Created`.

### Prueba 2 — Crear un Empleado
```bash
curl -i -X POST http://localhost:8080/empleados \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Juan",
    "apellido": "Perez",
    "email": "juan.perez@empresa.com",
    "cargo": "Developer",
    "departamentoId": "<ID_DEPARTAMENTO_CREADO>"
  }'
```
* **Esperado:** Código HTTP `201 Created` y publicación automática de `empleado.creado` en RabbitMQ.

### Prueba 3 — Verificar RabbitMQ
Ingresar a la consola en [http://localhost:15672](http://localhost:15672) (`admin` / `admin`) → Pestaña **Queues**.
* **Esperado:**
  * `notifications.queue` → `Ready = 0` (mensaje consumido con ACK).
  * `profiles.queue` → `Ready = 0`.
  * `vacations.queue` → `Ready = 0`.

### Prueba 4 — Ver Notifications
```bash
docker compose exec notifications-service curl -s http://localhost:8082/notifications
```
* **Esperado:** Historial con la notificación de bienvenida registrada.

### Prueba 5 — Ver Profiles (Read Model CQRS)
```bash
docker compose exec profiles-service curl -s http://localhost:8083/profiles
```
* **Esperado:** Lista de perfiles con el empleado creado y estado `ACTIVO`.

### Prueba 6 — Ver Vacations
```bash
docker compose exec vacations-service curl -s http://localhost:8084/vacations
```
* **Esperado:** Registro con `diasDisponibles = 15`, `diasTomados = 0` y `estadoEmpleado = ACTIVO`.

### Prueba 7 — Idempotencia
Reenviar el mismo mensaje con el mismo `id` (UUID).
* **Esperado:**
  * Log del consumidor registra `[DUPLICADO]`.
  * Confirmación con ACK sin duplicar registros en la base de datos PostgreSQL.

## 9. Decisiones Arquitectónicas del Proyecto

El historial formal de decisiones técnicas se documenta en [`docs/decisions/`](docs/decisions/):

* **[ADR-001](docs/decisions/ADR-001-motor-de-base-de-datos.md):** PostgreSQL en instancias separadas por servicio.
* **[ADR-002](docs/decisions/ADR-002-estrategia-de-esquema.md):** Migraciones versionadas (Flyway y Prisma Migrate).
* **[ADR-003](docs/decisions/ADR-003-garantia-de-unicidad.md):** Unicidad en dos capas (consulta previa + restricción UNIQUE).
* **[ADR-004](docs/decisions/ADR-004-rabbitmq-message-broker.md):** Selección de RabbitMQ como Message Broker oficial.
* **[ADR-005](docs/decisions/ADR-005-event-driven-architecture.md):** Adopción de Arquitectura Orientada a Eventos (EDA).
* **[ADR-006](docs/decisions/ADR-006-fanout-exchange.md):** Uso de Exchange Fanout `hr.events` para distribución simultánea 1 a N.
* **[ADR-007](docs/decisions/ADR-007-idempotent-consumers.md):** Consumidores idempotentes basados en Event ID.

## 10. Resultados Obtenidos del Reto 4

| Validación | Estado |
|---|---|
| Gateway funcionando | Completado |
| Employees Service (Productor) | Completado |
| Departments Service | Completado |
| Notifications Consumer | Completado |
| Profiles Consumer (Read Model CQRS) | Completado |
| Vacations Consumer | Completado |
| RabbitMQ Fanout (`hr.events`) | Completado |
| Idempotencia en consumidores | Completado |
| Database-per-Service (PostgreSQL independientes) | Completado |
