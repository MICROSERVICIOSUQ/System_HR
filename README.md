# Sistema de Recursos Humanos — System_HR (Retos 3 a 7)

> Actualización del desarrollador 3: Notificaciones migró a Go y Vacaciones usa fechaFin como regreso. Las instrucciones vigentes de esta parte están en [la instalación en otro PC](docs/GUIA-DESARROLLADOR3-OTRO-PC.md), [la guía de sustentación](docs/GUIA-DESARROLLADOR3-SUSTENTACION.md) y [la guía de integración del desarrollador 4](docs/GUIA-DESARROLLADOR4-INTEGRACION.md). Las secciones de retos anteriores conservan su contexto histórico.

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
| departments-service | Python 3.12, FastAPI, SQLAlchemy y Alembic | 8081 | Mediante Gateway |
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
| departments-service | Python + FastAPI | 8081 | PostgreSQL | Catálogo de departamentos |
| notifications-service | Node + Prisma | 8082 | PostgreSQL | Consumidor asíncrono (notificaciones) |
| profiles-service | Node + Prisma | 8083 | PostgreSQL | Consumidor asíncrono (Read Model CQRS) |
| vacations-service | Node + Prisma | 8084 | PostgreSQL | Consumidor asíncrono (saldo vacaciones) + scheduler (Reto 5) |
| **auth-service** | **Spring Boot** | **8085** | **PostgreSQL** | **Proveedor de identidad: JWT y ciclo de vida de cuentas (Reto 5)** |
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
| empleado.retirado | employees-service | notifications, profiles, vacations, **auth** | Retiro lógico del empleado |
| vacaciones.programadas | vacations-service | notifications | Período de vacaciones registrado |
| **vacaciones.iniciadas** | **vacations-service** (scheduler) | **auth**, notifications | Llegó la `fechaInicio` del período |
| **vacaciones.finalizadas** | **vacations-service** (scheduler) | **auth**, notifications | Pasó la `fechaFin` del período |
| **usuario.creado** | **auth-service** | notifications | Cuenta creada; lleva el token de activación |
| **usuario.recuperacion** | **auth-service** | notifications | Recuperación solicitada; lleva el token |
| **cuenta.activada** | **auth-service** | notifications | Contraseña inicial establecida, o fin de vacaciones |
| **cuenta.desactivada** | **auth-service** | notifications | Suspensión por vacaciones, o baja por retiro |

Los eventos en negrita se añadieron en el Reto 5. Las cargas útiles de
`usuario.*` y `cuenta.*` están detalladas en el
[README del auth-service](services/auth-service/README.md#ciclo-de-vida-de-la-cuenta).

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
│   ├── auth-service/
│   ├── employees-service/
│   ├── departments-service/
│   ├── notifications-service/
│   ├── profiles-service/
│   └── vacations-service/
├── e2e-tests/
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
* **`services/`**: Código fuente de los microservicios de dominio (Productores y Consumidores) y del `auth-service`.
* **`e2e-tests/`**: Suite BDD de extremo a extremo (Cucumber + Rest Assured) que verifica el sistema desplegado a través del gateway.
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

Los ADR posteriores (008 a 013) se listan en el [índice de decisiones](docs/decisions/README.md); cubren la validación del JWT en el Gateway, los estados de cuenta, la pirámide de pruebas, la verificación de efectos asíncronos, la reconstrucción de departamentos en Python y la regla para renombrar un campo ya publicado.

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

---

# Reto 5 — Seguridad y Control de Acceso con JWT

> [!TIP]
> Para **probar y demostrar** el reto paso a paso, con el orden exacto de
> ejecución y el nombre de cada carpeta y request de Postman, siga la
> [**Guía de pruebas del Reto 5**](docs/GUIA-PRUEBAS-RETO5.md).

## 11. Qué cambió

Hasta el Reto 4 todos los endpoints estaban abiertos: cualquiera que conociera la
IP y el puerto podía crear o eliminar empleados. El Reto 5 cierra el sistema:

- Un **`auth-service`** nuevo (puerto interno 8085) verifica credenciales, emite
  Access JWT y gestiona el ciclo de vida de las cuentas por eventos.
- El **api-gateway** valida la firma del JWT y aplica las reglas de autorización
  antes de enrutar.
- El **`vacations-service`** completa su scheduler: al llegar la fecha dispara
  `vacaciones.iniciadas` y `vacaciones.finalizadas`.
- El **`notifications-service`** simula los correos de seguridad y de cuenta.

## 12. Cómo obtener un token y hacer peticiones autenticadas

### Paso 1 — Login

El sistema crea un **administrador semilla** al arrancar (configurable con
`AUTH_SEED_ADMIN_*` en `.env`). Sin él no habría forma de empezar: las cuentas de
empleado nacen con rol `USER` desde el evento `empleado.creado`.

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"admin@empresa.com","password":"Admin12345"}'
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tipo": "Bearer",
  "expiraEnSegundos": 3600,
  "empleadoId": "admin",
  "rol": "ADMIN"
}
```

### Paso 2 — Usar el token

```bash
TOKEN="<pegue aquí el token>"

curl -s http://localhost:8080/empleados \
  -H "Authorization: Bearer $TOKEN"
```

En PowerShell:

```powershell
$token = (Invoke-RestMethod -Uri http://localhost:8080/auth/login -Method POST `
  -ContentType "application/json" `
  -Body '{"usuario":"admin@empresa.com","password":"Admin12345"}').token

Invoke-RestMethod -Uri http://localhost:8080/empleados -Headers @{ Authorization = "Bearer $token" }
```

### Paso 3 — El alta de un empleado real

El empleado **no recibe contraseña**: recibe un token de activación por evento.

```
ADMIN crea el empleado              POST /empleados
   └─► employees-service publica    empleado.creado
         └─► auth-service crea la cuenta (PENDIENTE_ACTIVACION, rol USER)
               y publica             usuario.creado  ← lleva el token
                     └─► notifications simula el correo con el enlace
```

El token aparece en el log de notificaciones:

```bash
docker compose logs notifications-service | grep "NOTIFICACIÓN"
```

```
[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: juan.perez@empresa.com | Mensaje: "Para establecer
su contraseña use el siguiente enlace: https://app.empresa.com/reset?token=eyJhbGciOi..."
```

Con ese token el empleado establece su contraseña y ya puede autenticarse:

```bash
curl -s -X POST http://localhost:8080/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"<token del log>","nuevaPassword":"Secreta123"}'
```

### Los dos tipos de token

| | Access JWT | Reset JWT |
|---|---|---|
| Lo emite | `POST /auth/login` | Alta de empleado o `/auth/recover-password` |
| Claims | `sub`, `role`, `iat`, `exp` | `sub`, `type: "RESET_PASSWORD"`, `iat`, `exp` |
| Sirve para | Acceder a cualquier recurso autorizado | Solo establecer una contraseña |
| Vigencia | `JWT_ACCESS_TTL_SECONDS` (1 h) | `JWT_RESET_TTL_SECONDS` (1 h) |

Ambos se firman con el mismo secreto, así que el claim `type` es lo único que los
separa. Por eso la validación va en las dos direcciones: un Access JWT **no**
sirve para cambiar una contraseña, y un Reset JWT **no** abre ningún recurso. Hay
un test automatizado para cada dirección.

La contraseña nunca entra en el token y en base de datos se guarda como **hash
BCrypt** con sal propia.

## 13. Estrategia de validación elegida: en el API Gateway

**Decisión: la validación del JWT y las reglas de autorización viven solo en el
api-gateway.** Registrada en
[ADR-008](docs/decisions/ADR-008-validacion-jwt-en-el-gateway.md).

```
Cliente ──Bearer JWT──► api-gateway ──X-Auth-User / X-Auth-Role──► microservicio
                             │
                   1. JwtAuthenticationFilter  → firma, emisor, exp, type → 401
                   2. AuthorizationFilter      → RBAC + propiedad         → 403
```

**Por qué el gateway y no interceptores por servicio.** El ecosistema tiene siete
componentes en dos lenguajes. Resolver la validación en cada servicio obligaría a
implementarla dos veces con librerías distintas (jjwt en Java, jose en Node) y a
mantener sincronizados el criterio de expiración, el manejo del claim `type` y la
distinción entre 401 y 403. Cada servicio nuevo añadiría otra copia. Y cuando la
validación pase a JWKS contra un Identity Provider, ese cambio se haría en un solo
sitio en lugar de en seis. Es exactamente el problema que el gateway del Reto 3 se
anticipó a resolver.

**Cómo se propaga la identidad.** Tras validar, el gateway añade `X-Auth-User`
(claim `sub`) y `X-Auth-Role` (claim `role`). Los servicios confían en esas
cabeceras, y eso es seguro por dos razones concretas:

1. **El gateway borra esas cabeceras si llegan del cliente**, antes de validar.
   Sin eso, cualquiera podría enviar `X-Auth-Role: ADMIN`. Hay un request en la
   colección de Postman que lo comprueba.
2. **Solo el gateway está publicado al host.** Los demás servicios usan `expose`,
   no `ports`: no son alcanzables desde fuera de la red de Docker, así que no hay
   forma de esquivar el borde.

**El secreto compartido.** `JWT_SECRET` se inyecta por variable de entorno a los
dos contenedores que lo necesitan: `auth-service` para firmar y `api-gateway` para
verificar. Ambos **abortan el arranque** si tiene menos de 32 bytes, en lugar de
firmar con una clave débil. Está documentado en `.env.example` con propósitos
académicos, tal como pide el enunciado; en producción iría en un gestor de
secretos y con firma asimétrica RS256.

## 14. Reglas de autorización (RBAC + propiedad del recurso)

| Situación | Resultado |
|---|---|
| Sin cabecera `Authorization` o con token inválido/expirado/alterado | **401 Unauthorized** |
| Rol `ADMIN`, cualquier método y ruta | **Permitido** |
| Rol `USER`, método de lectura (`GET`, `HEAD`, `OPTIONS`) | **Permitido** |
| Rol `USER`, `POST /auth/change-password` | **Permitido** (su propia contraseña) |
| Rol `USER`, `PUT`/`PATCH /perfiles/{id}` con `id == sub` | **Permitido** (su propio perfil) |
| Rol `USER`, cualquier otra escritura | **403 Forbidden** |

Las rutas públicas (no exigen token) son las imprescindibles para conseguir uno,
más salud y documentación: `/auth/login`, `/auth/recover-password`,
`/auth/reset-password`, `/health`, `/actuator/**` y las de Swagger.

`/auth/reset-password` es pública **a propósito**: su autorización no viene de una
sesión previa sino del token de un solo propósito que llega en el cuerpo. Exigir un
Access JWT ahí haría imposible la activación inicial, que es justo el momento en el
que el usuario todavía no puede iniciar sesión.

### Autorización por propiedad del recurso

El RBAC por rol no alcanza para el caso del perfil: un `USER` no puede escribir en
general, pero sí sobre lo suyo. Eso exige una segunda comprobación:

```
si rol == ADMIN                                 → permitir
si rol == USER y método es de lectura           → permitir
si rol == USER y recurso.empleadoId == token.sub → permitir
en cualquier otro caso                          → 403 Forbidden
```

El `empleadoId` de la ruta se compara contra el claim `sub`. Por eso el `sub` es
el identificador del empleado y no el email: el email es la credencial de entrada
y podría cambiar sin que los permisos ya concedidos se rompan.

```bash
# Con el JWT de E601
curl -X PUT http://localhost:8080/perfiles/E601 -H "Authorization: Bearer $TOKEN_E601" ...  # 200 OK
curl -X PUT http://localhost:8080/perfiles/E602 -H "Authorization: Bearer $TOKEN_E601" ...  # 403 Forbidden
```

Mismo token, mismo verbo, misma ruta salvo el id. La diferencia es quién es dueño
del dato: es el paso de RBAC a ABAC, y es lo que permite que cada empleado
gestione su perfil sin darle permisos de administrador.

## 15. Ciclo de vida de la cuenta

### Diagrama de secuencia

```
ADMIN   api-gateway  employees   RabbitMQ   auth-service   vacations   notifications
  │          │           │          │            │             │            │
  │ POST /empleados      │          │            │             │            │
  ├─────────►│           │          │            │             │            │
  │          │ valida JWT (ADMIN)   │            │             │            │
  │          ├──────────►│          │            │             │            │
  │          │           │ empleado.creado       │             │            │
  │          │           ├─────────►├───────────►│             │            │
  │          │           │          │   crea cuenta            │            │
  │          │           │          │   PENDIENTE_ACTIVACION   │            │
  │          │           │          │   rol USER, sin password │            │
  │          │           │          │◄───────────┤ usuario.creado (+token)  │
  │          │           │          ├─────────────────────────────────────►│
  │          │           │          │            │             │   [NOTIFICACIÓN]
  │          │           │          │            │             │   SEGURIDAD
  │                                                                         │
  │ ░░ el empleado toma el token del correo ░░                              │
  │                                                                         │
  │ POST /auth/reset-password (token, nuevaPassword)                        │
  ├─────────►│───────────────────────────────────►│             │            │
  │          │           │          │    ACTIVA  │             │            │
  │          │           │          │◄───────────┤ cuenta.activada          │
  │          │           │          ├─────────────────────────────────────►│
  │                                                                         │
  │ POST /auth/login ──► 200 + Access JWT                                   │
  │                                                                         │
  │ POST /vacaciones (fechaInicio=hoy, fechaFin=hoy)                        │
  ├─────────►│───────────────────────────────────────────────►│             │
  │          │           │          │            │   PROGRAMADA│            │
  │                                                                         │
  │ ░░ el scheduler detecta que llegó la fechaInicio ░░                     │
  │          │           │          │            │   EN_CURSO  │            │
  │          │           │          │◄───────────────────────┤ vacaciones.iniciadas
  │          │           │          │  SUSPENDIDA_TEMPORAL    │            │
  │          │           │          │◄───────────┤ cuenta.desactivada       │
  │          │           │          │            │  (VACACIONES, perm=false)│
  │          │           │          ├─────────────────────────────────────►│
  │                                                                         │
  │ POST /auth/login ──► 401  ← la cuenta está suspendida                   │
  │                                                                         │
  │ ░░ el scheduler detecta que pasó la fechaFin ░░                         │
  │          │           │          │            │  FINALIZADA │            │
  │          │           │          │◄───────────────────────┤ vacaciones.finalizadas
  │          │           │          │    ACTIVA  │             │            │
  │          │           │          │◄───────────┤ cuenta.activada          │
  │          │           │          │            │  (FIN_VACACIONES)        │
  │          │           │          ├─────────────────────────────────────►│
  │                                                                         │
  │ POST /auth/login ──► 200  ← vuelve a funcionar                          │
  │                                                                         │
  │ DELETE /empleados/{id}                                                  │
  ├─────────►│──────────►│ empleado.retirado     │             │            │
  │          │           ├─────────►├───────────►│             │            │
  │          │           │          │ DESACTIVADA_PERMANENTE   │            │
  │          │           │          │◄───────────┤ cuenta.desactivada       │
  │          │           │          │            │  (RETIRO, perm=true)     │
  │          │           │          ├─────────────────────────────────────►│
  │                                                                         │
  │ POST /auth/login ──► 401  ← definitivo                                  │
```

### Los cuatro estados

| Estado | Cómo se llega | ¿Login? | ¿Reactivable por fin de vacaciones? |
|---|---|---|---|
| `PENDIENTE_ACTIVACION` | `empleado.creado` | No | No |
| `ACTIVA` | `reset-password` o fin de vacaciones | **Sí** | — |
| `SUSPENDIDA_TEMPORAL` | `vacaciones.iniciadas` | No | **Sí** |
| `DESACTIVADA_PERMANENTE` | `empleado.retirado` | No | **No** |

El retiro es lógico: el registro y su `fechaRetiro` se conservan para auditoría.
Intentar retirar de nuevo un empleado que ya está en estado `RETIRADO` devuelve
**400** y no guarda cambios ni publica otro evento `empleado.retirado`.

El estado es una enumeración y no un booleano precisamente por el caso borde que
viene a continuación. Registrado en
[ADR-009](docs/decisions/ADR-009-estados-de-cuenta.md).

### Caso borde: retiro durante las vacaciones

Si un empleado se retira **mientras está de vacaciones**, al llegar la `fechaFin`
el scheduler publica `vacaciones.finalizadas` sobre una cuenta que ya tiene baja
permanente. **La cuenta no se reactiva.**

La garantía está en el modelo, no en una condición suelta:

```java
public boolean esReactivablePorVacaciones() {
    return this == SUSPENDIDA_TEMPORAL;
}
```

`DESACTIVADA_PERMANENTE` devuelve `false`, el manejador no toca la cuenta y el log
deja constancia:

```
[AUTH] NO se reactiva: estado 'DESACTIVADA_PERMANENTE' no es reactivable por
       vacaciones (caso borde retiro durante vacaciones) | empleado='E702'
```

Con un booleano `activa` esto sería imposible: el manejador no podría distinguir si
el `false` vino de unas vacaciones o de un retiro. Lo cubre el test
`retiroDuranteVacacionesImpideLaReactivacionAlFinalizarElPeriodo` y la carpeta 4 de
la colección `ciclo-vida-cuenta`.

## 16. Scheduler de vacaciones

### Mecanismo y frecuencia

| | |
|---|---|
| **Servicio** | `vacations-service` (Node + TypeScript) |
| **Mecanismo** | `node-cron` |
| **Frecuencia** | `VACACIONES_CRON`, por defecto `* * * * *` (cada minuto) |
| **Interruptor** | `VACACIONES_SCHEDULER_ENABLED` (`true` por defecto) |

Cada ciclo hace dos barridos:

1. Períodos `PROGRAMADA` cuya `fechaInicio` ya llegó → `EN_CURSO` + publica
   `vacaciones.iniciadas`.
2. Períodos `EN_CURSO` cuya `fechaFin` ya pasó → `FINALIZADA` + publica
   `vacaciones.finalizadas`.

La `fechaFin` es **inclusiva**: unas vacaciones que terminan hoy siguen vigentes
hoy, así que un período solo se finaliza cuando su fecha es estrictamente anterior
al día en curso.

Cada transición se hace con un **compare-and-set** en base de datos
(`UPDATE ... WHERE id = ? AND estado = ?`): si dos ciclos se solapan, el segundo
obtiene cero filas afectadas y no vuelve a publicar el evento. Además, un guardia
en memoria evita que un ciclo arranque si el anterior no terminó.

### Cómo demostrar el flujo sin esperar días

Se combinan dos de las tres estrategias que ofrece el enunciado:

**Estrategia 1 — fechas de hoy.** Programar el período con
`fechaInicio = fechaFin = hoy` y dejar que el scheduler (cada minuto) dispare el
inicio. Es lo que ocurre automáticamente.

**Estrategia 3 — endpoints de desarrollo.** Para la sustentación, donde esperar un
minuto por transición es incómodo y el fin de período exigiría esperar al día
siguiente:

```bash
curl -X POST http://localhost:8080/vacaciones/{id}/forzar-inicio \
  -H "Authorization: Bearer $TOKEN_ADMIN"

curl -X POST http://localhost:8080/vacaciones/{id}/forzar-fin \
  -H "Authorization: Bearer $TOKEN_ADMIN"
```

Ambos están **marcados como endpoints de desarrollo** y **protegidos con rol
ADMIN**. `forzar-inicio` es el que nombra el enunciado; `forzar-fin` es su
contraparte simétrica, necesaria para demostrar la reactivación y el caso borde en
segundos.

### Limitación conocida: una sola instancia

> El scheduler se ejecuta **en cada instancia** del `vacations-service`.

Mientras haya una sola instancia, funciona. Si el servicio se escalara a N
instancias, las N dispararían el mismo evento: N correos, N desactivaciones.

Hay dos defensas, y son complementarias:

1. **Deduplicación en el consumidor** (ya implementada, Reto 4). El `auth-service`
   descarta el evento repetido por su `eventId`. Mitiga el daño, pero no evita el
   trabajo duplicado: los N productores ya hicieron la consulta y la publicación.
2. **Coordinación en el productor** (no implementada). Garantizar que solo una
   instancia ejecute el job. Es lo que resuelve un lock distribuido en base de
   datos como **ShedLock**.

El compare-and-set del repositorio protege **dentro de un proceso**, no entre
procesos: dos instancias distintas pueden leer el mismo período antes de que
ninguna escriba, y entonces una de las dos gana el `UPDATE` pero la otra ya habrá
decidido publicar.

**En este reto se acepta la versión de instancia única.** El Reto 11 escala el
ecosistema horizontalmente y usará esta limitación como caso de estudio.

## 17. Documentación OpenAPI con BearerAuth

Cada servicio declara el esquema de seguridad `BearerAuth`, así que se puede pegar
el JWT en el botón **Authorize** de Swagger UI y probar la API sin Postman.

El gateway expone además una **Swagger UI agregada** con un desplegable por
servicio:

| Interfaz | URL |
|---|---|
| **Swagger UI agregada (gateway)** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |
| Contrato de auth-service | [http://localhost:8080/v3/api-docs/auth](http://localhost:8080/v3/api-docs/auth) |
| Contrato de employees-service | [http://localhost:8080/v3/api-docs/empleados](http://localhost:8080/v3/api-docs/empleados) |
| Contrato de departments-service | [http://localhost:8080/v3/api-docs/departamentos](http://localhost:8080/v3/api-docs/departamentos) |

Flujo recomendado: abrir la UI agregada, elegir *auth-service*, ejecutar
`POST /auth/login`, copiar el `token` de la respuesta, pulsar **Authorize**,
pegarlo, y a partir de ahí todas las operaciones de los demás servicios van
autenticadas.

## 18. Colecciones de Postman del Reto 5

En [`postman/`](postman/), todas con el environment
`system-hr.postman_environment.json` (variable `gatewayBaseUrl`):

| Colección | Qué demuestra |
|---|---|
| `auth-service.postman_collection.json` | Los cuatro endpoints, forma del JWT, política de contraseñas y la separación entre los dos tipos de token en ambas direcciones |
| `seguridad-rbac.postman_collection.json` | **401** sin token, con token alterado y con cabeceras falsificadas; **403** por rol; lectura de empleados permitida a `USER` y `ADMIN`; **propiedad del recurso** (`PUT /perfiles/E601` = 200 vs `/perfiles/E602` = 403 con el mismo token) |
| `ciclo-vida-cuenta.postman_collection.json` | La cadena completa `login OK → vacaciones inician → login falla → vacaciones terminan → login OK`, y el **caso borde** del retiro durante vacaciones |

```bash
npx newman run postman/seguridad-rbac.postman_collection.json \
  -e postman/system-hr.postman_environment.json
```

> [!IMPORTANT]
> Las colecciones del ciclo de vida tienen requests marcados **[MANUAL]**: hay que
> copiar el token de activación desde el log de notificaciones a una variable de
> colección. No es automatizable sin leer los logs, porque el token viaja por
> evento y nunca por respuesta HTTP — que es justamente el comportamiento correcto.
> El request anterior imprime el comando exacto en la consola de Postman.

Si el puerto 8080 está ocupado en su máquina, levante con `GATEWAY_PORT=8090` y
ajuste `gatewayBaseUrl` en el environment, o pase `--env-var` a Newman.

## 19. Variables de entorno del Reto 5

Todas en `.env.example`; ninguna credencial está quemada en el código.

| Variable | Por defecto | Para qué |
|---|---|---|
| `GATEWAY_PORT` | `8080` | Único puerto publicado al host |
| `JWT_SECRET` | *(obligatorio)* | Secreto HS256 compartido. Mínimo 32 bytes |
| `JWT_ISSUER` | `system-hr-auth` | Claim `iss`, verificado al validar |
| `JWT_ACCESS_TTL_SECONDS` | `3600` | Vigencia del Access JWT |
| `JWT_RESET_TTL_SECONDS` | `3600` | Vigencia del token de activación/recuperación |
| `AUTH_PORT` | `8085` | Puerto interno del auth-service |
| `AUTH_DB_*` | `gestion_auth`, `postgres` | Su base de datos propia |
| `AUTH_SEED_ADMIN_ID` | `admin` | `empleadoId` del administrador semilla |
| `AUTH_SEED_ADMIN_EMAIL` | `admin@empresa.com` | Su email de login |
| `AUTH_SEED_ADMIN_PASSWORD` | `Admin12345` | Su contraseña inicial |
| `VACACIONES_CRON` | `* * * * *` | Frecuencia del scheduler |
| `VACACIONES_SCHEDULER_ENABLED` | `true` | Interruptor del scheduler |

El compose usa `${JWT_SECRET:?...}`: si la variable no está definida, **el
levantamiento falla con un mensaje claro** en lugar de arrancar con un secreto por
defecto conocido.

## 20. Evidencias del Reto 5

Salida real capturada el 2026-10-20 tras `docker compose down -v && docker compose up -d`.
En esa máquina el 8080 estaba ocupado, así que el gateway se publicó con
`GATEWAY_PORT=8090`; nada más cambia.

### Arranque ordenado

```console
$ docker compose ps
NAME                                  STATUS
system_hr-api-gateway-1               Up 42 seconds (healthy)
system_hr-auth-service-1              Up About a minute (healthy)
system_hr-database-departamentos-1    Up About a minute (healthy)
system_hr-database-empleados-1        Up About a minute (healthy)
system_hr-database-notificaciones-1   Up About a minute (healthy)
system_hr-database-perfiles-1         Up About a minute (healthy)
system_hr-db-auth-1                   Up About a minute (healthy)
system_hr-db-vacaciones-1             Up About a minute (healthy)
system_hr-departments-service-1       Up About a minute (healthy)
system_hr-employees-service-1         Up 52 seconds (healthy)
system_hr-notifications-service-1     Up About a minute (healthy)
system_hr-profiles-service-1          Up About a minute (healthy)
system_hr-rabbitmq-1                  Up About a minute (healthy)
system_hr-vacations-service-1         Up About a minute (healthy)
```

14 de 14 contenedores `healthy`, sin errores de conexión en los logs.

### Forma del Access JWT

```console
$ header del token devuelto por POST /auth/login
{"alg":"HS256"}

$ payload
{"iss":"system-hr-auth","sub":"admin","role":"ADMIN","iat":1792535169,"exp":1792538769}
```

Firma HMAC-SHA256, los cuatro claims que pide el enunciado y **ninguna
contraseña**.

### Cadena de eventos del onboarding

```console
$ docker compose logs auth-service
[AUTH] [RECIBIDO] empleado.creado | eventId='c43eeb2b-...' | empleado='E701'
[AUTH] Cuenta creada en PENDIENTE_ACTIVACION con rol USER | empleado='E701'
[AUTH] [PUBLICADO] usuario.creado | eventId='e75a2ef5-...' | empleado='E701'
```

### Caso borde: retiro durante vacaciones

```console
$ docker compose logs auth-service
[AUTH] NO se reactiva: estado 'DESACTIVADA_PERMANENTE' no es reactivable por
       vacaciones (caso borde retiro durante vacaciones) | empleado='E702'
```

Y ningún `cuenta.activada` para E702: el login del empleado retirado sigue
fallando después de que su período de vacaciones terminara.

### Verificación capturada el 2026-10-20: 37 comprobaciones

| Bloque | Comprobaciones | Resultado |
|---|---|---|
| Validación de token (401 sin token, con firma falsa, con cabeceras falsificadas) | 3 | Todas OK |
| Login del administrador semilla y forma del JWT | 1 | OK |
| Onboarding dirigido por eventos | 2 | Todas OK |
| Activación, política de contraseñas y separación de los dos tokens | 6 | Todas OK |
| RBAC y propiedad del recurso | 7 | Todas OK |
| Scheduler y ciclo de vacaciones (`login OK → falla → OK`) | 5 | Todas OK |
| Caso borde del retiro durante vacaciones | 6 | Todas OK |
| Auditoría y documentación OpenAPI | 7 | Todas OK |
| **Total** | **37** | **37 OK, 0 fallos** |

La colección `Seguridad y RBAC` actual añade `5.4 GET /empleados con token ADMIN
(200)`, que no formó parte de esta captura histórica. El Bloque B suma ahora 21
requests; junto con los demás bloques, las colecciones del Reto 5 documentan 38
comprobaciones automatizadas.

Los dos eslabones clave de la cadena, con el mensaje real del servicio:

```console
$ POST /auth/login (durante las vacaciones)
HTTP 401 — "La cuenta está suspendida temporalmente porque el empleado se
            encuentra de vacaciones"

$ POST /auth/login (tras forzar-fin)
HTTP 200
```

### Pruebas automatizadas del auth-service

```console
$ cd services/auth-service && ./mvnw clean test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  -- AccountLifecycleTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- AuthEndpointsTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

# Reto 6 — Automatización de Pruebas

La captura del Reto 5 registra 37 comprobaciones de Postman. La colección actual
añade la lectura de empleados con rol `ADMIN`, para un total documentado de 38.
Aunque funciona, esta verificación no se puede repetir en un pipeline: alguien
tiene que hacer clic, leer la respuesta y decidir si está bien. El Reto 6 la
convierte en código que se ejecuta con un comando y devuelve un código de salida
que una máquina puede interpretar.

La guía paso a paso con el orden exacto de ejecución y el resultado esperado de
cada comando está en **[docs/GUIA-PRUEBAS-RETO6.md](docs/GUIA-PRUEBAS-RETO6.md)**.

## 21. Qué se añadió

| Elemento | Ubicación | Contenido |
|---|---|---|
| Pruebas unitarias TypeScript | `services/vacations-service/src/tests/` | 54 pruebas con `node:test` y dobles escritos a mano |
| Módulo de dominio puro | `services/vacations-service/src/domain/vacation-rules.ts` | Las reglas de fechas y solapamiento, extraídas de la consulta de Prisma |
| Pruebas unitarias Java | `services/employees-service/src/test/.../EmployeeServiceUnitTest.java` | 17 pruebas con Mockito, sin contexto de Spring |
| Prueba de integración | `services/employees-service/src/test/.../EmployeeRepositoryIntegrationTest.java` | 7 pruebas contra PostgreSQL 16 real vía Testcontainers |
| Cobertura con umbral | `services/employees-service/pom.xml` | JaCoCo con mínimo del 70 % de líneas que rompe el build |
| Suite BDD | `e2e-tests/` | 39 escenarios en Gherkin español con Cucumber y Rest Assured |
| Decisiones | `docs/decisions/ADR-010`, `ADR-011` | Pirámide de pruebas; verificación de efectos asíncronos |
| Evidencias | `docs/evidencias/reto6-*.txt` | Mutaciones detectadas, fallo deliberado y tres ejecuciones consecutivas |

## 22. La pirámide de pruebas

Los tres niveles verifican cosas distintas y fallan por razones distintas. Esa
separación es lo que permite que un fallo señale su propia causa.

```text
                    ┌─────────────────────────┐
                    │   BDD / E2E  (39)       │  Flujos de negocio completos
                    │   e2e-tests/            │  Necesita el ecosistema arriba
                    ├─────────────────────────┤
                 ┌──┤  Integración  (7)       │  Que el esquema exista de verdad
                 │  │  Testcontainers         │  Necesita Docker
              ┌──┴──┴─────────────────────────┤
              │     Unitarias  (71)           │  Reglas de negocio
              │     Mockito / node:test       │  Solo el runtime del lenguaje
              └───────────────────────────────┘
```

- **Unitario (71 pruebas, dos lenguajes).** Verifica reglas de negocio con todas
  las dependencias sustituidas por dobles. **Ninguna prueba unitaria abre un
  socket ni necesita Docker ni base de datos.** Corren en segundos.
- **Integración (7 pruebas).** Verifica lo único que el nivel inferior no puede:
  que Flyway aplicó las migraciones y que las restricciones `UNIQUE` y `NOT NULL`
  están **en el esquema de PostgreSQL** y no solo en el código Java. Se omiten
  limpiamente donde no hay Docker, en lugar de fallar en rojo.
- **BDD / extremo a extremo (39 escenarios).** Verifica flujos completos a través
  del API Gateway, incluidos los que atraviesan RabbitMQ y varios servicios.

Dos consecuencias de diseño que merecen mención:

1. **El predicado de solapamiento se extrajo a un módulo puro.** Vivía dentro de
   una consulta de Prisma, donde solo se podía verificar con una base de datos
   real. Ahora la consulta filtra por empleado y estado —conjunto acotado— y el
   predicado se aplica en memoria, de modo que la prueba unitaria cubre el código
   que de verdad corre en producción y no una copia de la regla.
2. **El doble no puede contradecir a producción.** `RepositorioFake` resuelve los
   solapamientos reutilizando `periodosSolapados`, la misma función que usa el
   repositorio real. Un doble con su propia copia de la regla puede quedar en
   verde mientras producción está roto.

El razonamiento completo está en
[ADR-010](docs/decisions/ADR-010-piramide-de-pruebas.md).

## 23. BDD: por qué los escenarios están en español

Un escenario escrito en Gherkin lo puede leer y corregir alguien que no programa.
Eso es lo que hace posible la conversación de los *Three Amigos* —negocio,
desarrollo y pruebas acordando el comportamiento **antes** de escribir el
código— y lo que convierte la suite en documentación que no se queda obsoleta:
si el `.feature` miente, la suite se pone roja.

```gherkin
# language: es
Característica: Offboarding de empleados
  Como administrador del sistema
  Quiero desvincular empleados
  Para que pierdan el acceso de forma inmediata y definitiva
  Pero su historial se conserve para auditoría

  Antecedentes:
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento para las pruebas
    Y que existe un empleado activo con credenciales establecidas

  Escenario: Desvinculación completa de un empleado
    Dado que el empleado puede iniciar sesión
    Cuando retiro al empleado
    Entonces la respuesta debe tener código 200
    Y eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
    Y el motivo de la desactivación debe ser "RETIRO"
    Y eventualmente el empleado no puede iniciar sesión
    Y el mensaje de error debe contener "permanente"
    Y eventualmente el empleado solicita recuperar su contraseña y es rechazado
    Entonces la respuesta debe tener código 401
```

Los pasos que empiezan por **«eventualmente»** son los que atraviesan el broker:
no se verifican con una espera fija sino con reintentos, porque una
`Thread.sleep` paga siempre su peor caso y además falla de forma intermitente el
día que el evento tarda un poco más. Las transiciones de tiempo —que las
vacaciones empiecen y terminen— se provocan con los endpoints de disparo manual
del Reto 5, que ejecutan la misma transición y publican el mismo evento que el
scheduler, para no acoplar la prueba a su frecuencia.

El detalle de la suite —el «World», las variables de entorno, el catálogo de
escenarios y cómo añadir uno— está en **[e2e-tests/README.md](e2e-tests/README.md)**,
y las decisiones sobre las esperas en
[ADR-011](docs/decisions/ADR-011-verificacion-de-efectos-asincronos.md).

## 24. Comandos y reportes

Un comando por suite. Las tres devuelven código de salida distinto de cero si
algo falla, y las rutas de los reportes son fijas: son el contrato que consumirá
el pipeline de Jenkins del Reto 7.

| Suite / Microservicio | Comando de ejecución | Reporte de resultados | Reporte de cobertura |
|---|---|---|---|
| `employees-service` (unitarias + integración) | `cd services/employees-service && mvn verify` (o `.\mvnw.cmd verify`) | `target/surefire-reports/TEST-*.xml` | `target/site/jacoco/jacoco.xml` |
| `vacations-service` (unitarias) | `cd services/vacations-service && npm test` | `reports/junit.xml` | `reports/lcov.info` |
| `e2e-tests` (BDD) | `cd e2e-tests && .\mvnw.cmd test` / Docker `docker compose --profile testing run --build --rm bdd-tests` | `reports/junit.xml`, `reports/cucumber.json`, `reports/cucumber.html` | N/A |

Todos los comandos fallan con código de salida distinto de cero si falla una
prueba; `employees-service` también falla cuando JaCoCo detecta menos del 70 %
de cobertura de líneas. Jenkins puede detener el pipeline ante cualquier salida
distinta de cero y publicar los archivos de resultados indicados. SonarQube puede
consumir `jacoco.xml` y `lcov.info` como informes de cobertura. Las rutas de la
tabla son relativas a la carpeta de cada proyecto; BDD genera sus rutas relativas
a `e2e-tests/` (y dentro del contenedor, a `/app/`).

### Prerrequisitos y variables

- Java 21 para los dos proyectos Maven; Node.js compatible con el proyecto y
  `npm` para `vacations-service`; Docker Engine y Docker Compose para integración
  con PostgreSQL real y BDD en contenedor.
- Para ejecutar el ecosistema, cree `.env` desde `.env.example` y configure
  `JWT_SECRET` con al menos 32 caracteres. El mismo secreto se inyecta en
  `auth-service` y `api-gateway`; no hay valor de fallback y Compose no arranca
  esos servicios si falta.
- `BASE_URL` apunta al gateway: `http://localhost:8080` al ejecutar BDD desde el
  host y `http://api-gateway:8080` dentro de Compose.
- `ADMIN_USER` / `ADMIN_PASSWORD` deben coincidir con
  `AUTH_SEED_ADMIN_EMAIL` / `AUTH_SEED_ADMIN_PASSWORD` del administrador semilla.
  La suite crea sus propios usuarios regulares con `REGULAR_USER` como prefijo
  de correo y `REGULAR_PASSWORD` como contraseña; cada escenario usa UUIDs para
  evitar colisiones. `POLL_MAX_INTENTOS` y `POLL_INTERVALO_MS` ajustan las
  verificaciones de efectos asíncronos.

### Pirámide de pruebas y ejecución manual

- **Unitarias:** la base rápida y amplia. `vacations-service` prueba reglas de
  dominio con `node:test`; `employees-service` prueba servicios Java con JUnit y
  Mockito, sin levantar el ecosistema.
- **Integración:** `employees-service` verifica repositorio, esquema y
  restricciones contra PostgreSQL real mediante Testcontainers durante `mvn
  verify`; requiere Docker. Si Docker no está disponible, esas pruebas pueden
  omitirse según su configuración.
- **BDD / E2E:** la capa superior prueba flujos completos a través del Gateway,
  incluyendo propagación RabbitMQ y autorización; requiere los servicios
  saludables y datos de prueba.

Desde la raíz, ejecute cada suite:

```powershell
Set-Location services\employees-service
mvn verify

Set-Location ..\vacations-service
npm test

Set-Location ..\..\e2e-tests
.\mvnw.cmd test
```

Para BDD local, arranque primero el ecosistema con `docker compose up -d` y
configure `BASE_URL` si el gateway usa otro puerto. Para ejecutarla en contenedor
y conservar los reportes en `e2e-tests/reports/`:

```powershell
docker compose --profile testing run --build --rm bdd-tests
```

El perfil `testing` mantiene la suite fuera del `docker compose up` habitual. El
servicio BDD espera a que Gateway, autenticación, empleados, departamentos,
vacaciones y notificaciones estén saludables; el volumen de reportes permanece
montado aunque el contenedor finalice. Para ejecutar todo el sistema y las
pruebas desde cero se puede usar:

```powershell
docker compose --profile testing up --build --abort-on-container-exit --exit-code-from bdd-tests
```

El comando anterior usa el código de salida de la suite para detener CI y deja
los reportes en el host. Los servicios de negocio y sus bases de datos permanecen
administrables con `docker compose down` (sin `-v` para conservar los datos).

Requisitos por suite:

- Las dos primeras **no necesitan nada levantado**: ni contenedores, ni base de
  datos, ni variables de entorno. La de Java levanta un PostgreSQL efímero con
  Testcontainers si hay Docker disponible, y omite esas 7 pruebas si no lo hay.
- La BDD necesita el ecosistema arriba (`docker compose up -d`, 14 servicios
  `healthy`). Si el gateway no está en el 8080 se indica con
  `BASE_URL=http://localhost:8090`.

Los directorios de reportes están en `.gitignore`: la ruta es parte del contrato,
el contenido se regenera en cada ejecución.

## 25. Cobertura

| Servicio | Herramienta | Líneas | Umbral que rompe el build |
|---|---|---|---|
| `employees-service` | JaCoCo 0.8.12 | **90,25 %** | 70 % (`BUNDLE / LINE / COVEREDRATIO`) |
| `vacations-service` | `node:test --experimental-test-coverage` | **71,46 %** | 70 % |

Los filtros de cobertura son distintos por lenguaje. En `vacations-service` se
excluyen pruebas, archivos generados, repositorios y mensajería; se mide la
configuración, el dominio y el servicio. El script `npm test` aplica un umbral
del 70 % mediante `--test-coverage-lines=70`. Dos aclaraciones sobre los números:

- En `vacations-service` la medición **excluye los propios archivos de prueba**
  (`src/tests/**`), los archivos generados (`src/generated/**`), repositorios
  (`src/repositories/**`) y mensajería (`src/messaging/**`). El 71,46 % es el
  resultado actual; dentro de él, el módulo de dominio `vacation-rules.ts` está
  al 95,35 %. Repositorio y mensajería quedan fuera de este reporte unitario.
- En `employees-service` el umbral no es decorativo: `./mvnw verify` falla si la
  cobertura baja del 70 %.
- En `vacations-service`, `npm test` también falla si la cobertura de líneas baja
  del 70 %.

## 26. Resultados verificados del Reto 6

| Métrica | Resultado |
|---|---|
| Pruebas unitarias TypeScript | 54 / 54 |
| Pruebas unitarias Java | 17 / 17 |
| Pruebas de integración con PostgreSQL real | 7 |
| Escenarios BDD | **39 / 39** |
| Cobertura `employees-service` | 90,25 % con umbral del 70 % aplicado |
| Cobertura `vacations-service` | 71,46 % (dominio al 95,35 %) |
| Ejecuciones consecutivas sin fallos intermitentes (evidencia histórica de la versión con 41 escenarios) | 3 (36,34 s · 44,33 s · 31,68 s) |
| Reglas de negocio rotas a propósito y detectadas | 2 de 2 |
| Código de salida con un escenario fallido | 1 |

```console
$ cd e2e-tests && BASE_URL=http://localhost:8090 ./mvnw test
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Las evidencias de los tres últimos puntos están en `docs/evidencias/`:
[mutaciones](docs/evidencias/reto6-mutaciones.txt),
[fallo deliberado](docs/evidencias/reto6-bdd-fallo-deliberado.txt) y
[tres ejecuciones](docs/evidencias/reto6-bdd-ejecuciones.txt).
Los archivos de evidencia de la suite BDD conservan las salidas originales de la
versión de 41 escenarios; la suite vigente tiene 39.

---

# Reto 7 — Integración continua con Jenkins

Jenkins verifica empleados (Java) y vacaciones (TypeScript) mediante pipelines
versionados junto a cada servicio. Cada ejecución prepara el proyecto, ejecuta
las pruebas del reto 6, publica resultados y cobertura, envía el análisis a
SonarQube y exige una cobertura mínima del 70 %. Después construye la imagen
Docker y ejecuta los escenarios BDD contra una copia aislada del sistema.

La configuración de Jenkins, sus plugins, usuarios y jobs se encuentra en
`jenkins/`. SonarQube se prepara automáticamente con `ci/configurar-sonar.py`.
Los comandos de arranque son:

```bash
python3 ci/preparar-entorno.py
docker compose up -d --build
```

Jenkins se abre en http://localhost:9091 y SonarQube en http://localhost:9000.
El usuario inicial es `admin`; las contraseñas generadas están en `.env`, en
`JENKINS_ADMIN_PASSWORD` y `SONAR_ADMIN_PASSWORD`. No se usa el asistente inicial
de Jenkins: JCasC crea el administrador y los dos jobs. Antes de ejecutar un
pipeline, sube los archivos del reto a la rama indicada por
`CI_REPOSITORY_BRANCH` y revisa `CI_REPOSITORY_URL`.
Como el repositorio es privado, configura también `CI_GIT_USERNAME` y un PAT de
GitHub con `Contents: Read-only` en `.env`; Jenkins lo carga como credencial SCM
sin incluirlo en los archivos versionados.

| Etapa | Reporte | Publicador | Consulta |
|---|---|---|---|
| Test de empleados | Surefire XML y JaCoCo XML | `junit`, `recordCoverage` | Test Result y Coverage |
| Test de vacaciones | JUnit XML y LCOV | `junit`, `recordCoverage` | Test Result y Coverage |
| SonarQube / Quality Gate | Métricas y cobertura | Scanner y `waitForQualityGate` | Proyecto de SonarQube y etapa de Jenkins |
| Package | Diagnóstico Docker en HTML y texto | `publishHTML`, `archiveArtifacts` | Empaquetado Docker |
| E2E Tests | JUnit, Cucumber JSON y HTML | `junit`, `cucumber`, `publishHTML` | Test Result y escenarios BDD en español |
| Limpieza E2E | Logs del sistema | `archiveArtifacts` | Artefactos del build |

Las publicaciones se ejecutan aunque fallen las pruebas. Un fallo de pruebas
termina en FAILURE y evita el empaquetado; no se permite continuar con un build
UNSTABLE. Los volúmenes de E2E se eliminan al terminar y están separados de las
bases de datos habituales. Los jobs consultan cambios en Git cada cinco minutos
después de su primera ejecución.

La [guía del reto 7](docs/GUIA-RETO7.md) explica las etapas, el acceso, las cachés,
las decisiones de seguridad, el alcance de cobertura y cómo interpretar los
resultados. También describe las cuatro simulaciones disponibles en el parámetro
`FALLO_DEMO` y cómo reunir las capturas y las tres ejecuciones consecutivas.

**Validación realizada en `valentina`:** tres builds consecutivos correctos de
cada servicio, 39 escenarios BDD por ejecución y cuatro fallos deliberados
detectados en su etapa correspondiente. Las capturas, los números de build y
los resultados exportados están en las
[evidencias del reto 7](docs/evidencias/reto7/README.md). `main` no se modificó.
Los pipelines se verificaron mediante una copia Git local de `valentina`.

| Verificación | Empleados | Vacaciones |
|---|---|---|
| Tres builds consecutivos | #9, #10, #11 | #8, #9, #10 |
| Pruebas de servicio aprobadas por ejecución | 77 | 64 |
| Escenarios BDD aprobados por ejecución | 39 | 39 |
| Cobertura combinada de SonarQube | 82,4 % | 78,9 % |

Las simulaciones de prueba unitaria, Dockerfile, escenario BDD y cobertura baja
se documentaron en los builds #11, #15, #13 y #14 de vacaciones, respectivamente.
Después se restauraron las ejecuciones normales.

---

# Remediación de la auditoría — Dev 1 y Dev 2

La auditoría estática del sistema dejó una lista de hallazgos repartida entre
cuatro desarrolladores, cada uno con dos servicios y un lenguaje. Los dos
primeros lotes están implementados y verificados.

El detalle completo —el porqué de cada decisión, los códigos de error, las
trampas operativas y cómo comprobar cada punto— está en
**[docs/REMEDIACION-AUDITORIA.md](docs/REMEDIACION-AUDITORIA.md)**.

## 27. Qué cambió

| Dev | Servicio | Cambio |
|---|---|---|
| 1 | employees-service | `GET /empleados?desde=&hasta=` es la ruta canónica de la auditoría de retiros; `/empleados/auditoria` delega en ella y queda como alias `@Deprecated` |
| 1 | employees-service | `fechaRetiro` pasa de `LocalDate` a `LocalDateTime` (migración `V3`, `TIMESTAMP(3)`) |
| 1 | departments-service | Reconstruido en **Python 3.12 / FastAPI**, con Swagger UI nativo en `/docs` y el contrato generado de los modelos Pydantic |
| 2 | auth-service | `POST /auth/recover-password` exige que la cuenta esté `ACTIVA`; responde 401 con un mensaje distinto por estado |
| 2 | auth-service | El `healthcheck` de Compose apunta a `/health`, que sí comprueba base y broker, en lugar de `/auth/health`, que respondía 200 siempre |
| 2 | profiles-service | Cinco campos canónicos (`telefono`, `direccion`, `ciudad`, `biografia`, `fechaCreacion`), `employeeId` → `empleadoId` con alias, y marca `archivado` |
| 2 | api-gateway | 14 pruebas de la regla ABAC —incluido el 403 del `USER` que edita un perfil ajeno— con `MockServerWebExchange`, sin abrir sockets |

Las versiones de Java se verificaron antes de tocar nada: `employees-service` y
`auth-service` ya estaban en **Java 21**, y el `api-gateway` se dejó
deliberadamente en **Java 17**.

## 28. Resultados verificados de la remediación

| Suite | Resultado |
|---|---|
| `auth-service` | **24 / 24** |
| `api-gateway` (ABAC) | **14 / 14** |
| `departments-service` | **50 / 50**, 80,18 % con umbral del 70 % |
| `employees-service` | **60 verdes**, 7 de integración omitidas |
| `profiles-service` (`tsc --noEmit`) | sin errores |
| Escenarios BDD | **39 / 39** |
| Colecciones de Postman (Newman) | **5 colecciones, 0 fallos** |

Las 21 pruebas que levantan un servidor en loopback (14 de `employees-service` y
7 de `GatewayTest`) no se pueden ejecutar en este equipo —la misma restricción
por la que Tomcat no arranca aquí— y no cubren ninguno de estos cambios. El
detalle está en el documento de remediación.

## 29. Las colecciones de Postman después de los cambios

| Colección | Peticiones | Aserciones |
|---|---|---|
| `auth-service` | 22 | 49 |
| `perfiles-service` | 19 | 38 |
| `empleados-service` | 17 | 38 |
| `departamentos-service` | 12 | 24 |
| `observabilidad` | 18 | 39 |

Son **secuenciales y con estado**: hay que ejecutarlas completas y en orden, con
el entorno `system-hr` seleccionado. La primera petición de cada una es un login
de ADMIN y es obligatoria; sin el token, el Gateway responde 401 a todo lo demás.

```bash
newman run postman/auth-service.postman_collection.json -e postman/system-hr.postman_environment.json
```

Dos de ellas estaban rotas desde el Reto 5 por no enviar el JWT —`departamentos`
fallaba 17 de 17 aserciones y `empleados` 15 de 36— y nadie lo había notado
porque no se ejecutaban completas. Quedaron corregidas junto con este lote.
