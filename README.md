# Sistema de Recursos Humanos — Reto 3

Sistema de gestión de empleados y departamentos con microservicios, bases PostgreSQL independientes, un API Gateway y protección de la comunicación síncrona mediante Circuit Breaker.

## Arquitectura

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

## Servicios y tecnologías

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

## URL base y rutas

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

## Iniciar el sistema

Requisitos: Docker y Docker Compose con soporte para `include`. Python 3 solo es necesario para ejecutar la prueba integral.

Desde la raíz del repositorio:

```bash
docker compose up --build -d
docker compose ps
curl -i http://localhost:8080/health
curl -i http://localhost:8080/empleados
curl -i http://localhost:8080/departamentos
```

Las variables y valores predeterminados están en `.env.example`. Si necesitas personalizarlos, crea `.env` sin sobrescribir uno existente. `EMPLEADOS_PORT` y `DEPARTAMENTOS_PORT` configuran puertos internos; el puerto público del Gateway permanece en 8080.

Las migraciones Flyway y Prisma Migrate se ejecutan durante el arranque. Los datos persisten en volúmenes de PostgreSQL. Para detener conservando los datos:

```bash
docker compose down
```

## Circuit Breaker

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

## Estrategia de fallback

Si departamentos no está disponible o el circuito está abierto, el registro devuelve `503 Service Unavailable`, con el mensaje:

```json
{"message":"El servicio de departamentos no está disponible temporalmente"}
```

Este es el mensaje relevante; la respuesta incluye también `timestamp`, `status`, `error` y `path`.

Se prioriza la consistencia: no se guarda un empleado cuyo departamento no pudo validarse. El usuario debe reintentar después de la recuperación. No se crean registros pendientes y, por tanto, no se necesita reconciliación de esos registros. Las consultas que no dependen de departamentos pueden seguir funcionando si la base de empleados está disponible.

## Prueba reproducible del Reto 3

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

## Resultados verificados

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

Los tiempos dependen del entorno. La evidencia relevante es que las solicitudes con el circuito abierto evitan las llamadas de red y responden mucho más rápido.

Para la entrega, adjuntar las capturas o el video de estas comprobaciones y la salida de la prueba; los resultados históricos del Reto 2 deben identificarse como tales.

## Postman

Importa el environment y las colecciones de `postman/`. Las variables `empleadosBaseUrl` y `departamentosBaseUrl` deben tener el mismo valor: `http://localhost:8080`.

Las colecciones existentes utilizan identificadores fijos; pueden devolver errores por duplicados al repetirse sobre una base que ya contiene esos registros. Para demostrar resiliencia con identificadores nuevos, utiliza el script del Reto 3.

## Persistencia y decisiones previas

Se conservan PostgreSQL por servicio, las migraciones versionadas y las restricciones de unicidad. Las decisiones del Reto 2 están documentadas en `docs/decisions/`. Los reintentos de base de datos son independientes del Circuit Breaker de departamentos.

## Organización

- `api-gateway/`: entrada pública y enrutamiento.
- `services/`: servicios, contratos y migraciones.
- `infrastructure/`: configuración de bases de datos.
- `tests/`: prueba integral del ecosistema.
- `postman/`: colecciones y environment.
- `docs/`: arquitectura y decisiones técnicas.

La [evidencia de ejecución verificada](docs/evidencias/reto3-evidencia.jsonl) contiene respuestas, tiempos, estados y resultados de pruebas. Las capturas o el video exigidos para la entrega todavía deben adjuntarse.

## Infraestructura de mensajería

### Justificación técnica de RabbitMQ

En el Reto 4 se incorporan nuevos microservicios (perfiles, notificaciones, vacaciones) que deben reaccionar a eventos producidos por los servicios existentes —por ejemplo, cuando se registra un empleado nuevo. Una llamada HTTP síncrona acoplaría los servicios: si notificaciones no responde, el registro de empleados fallaría. RabbitMQ resuelve esto desacoplando productores y consumidores mediante colas de mensajes:

- El productor (por ejemplo, employees-service) publica un evento y continúa sin esperar respuesta.
- Cada consumidor (perfiles-service, notificaciones-service, vacaciones-service) procesa el mensaje de forma independiente y a su ritmo.
- Si un consumidor está caído, RabbitMQ retiene el mensaje hasta que vuelva a estar disponible.
- La falla de un consumidor no afecta a los demás ni al productor.

### Exchange Fanout

Un **Exchange Fanout** distribuye cada mensaje recibido a **todas** las colas vinculadas a él, sin filtrar por routing key. Es el patrón indicado cuando un mismo evento debe ser procesado por múltiples servicios. Por ejemplo, el evento `empleado.creado` puede enviarse al exchange `empleados_events` de tipo fanout y llegar simultáneamente a las colas de perfiles, notificaciones y vacaciones.

```
Producer ──► Exchange (fanout) ──► Cola perfiles      ──► profiles-service
                               └──► Cola notificaciones ──► notifications-service
                               └──► Cola vacaciones     ──► vacations-service
```

### Puertos y panel de administración

| Puerto | Uso |
|---|---|
| `5672` | Protocolo AMQP — conexión de aplicaciones |
| `15672` | Panel web de administración (Management UI) |

Accede al panel de administración en:

```
http://localhost:15672
```

| Campo | Valor |
|---|---|
| Usuario | `admin` |
| Contraseña | `admin` |

### Diagrama de arquitectura actualizado (Reto 4)

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

Mensajería asíncrona (Reto 4)
    employees-service ──► RabbitMQ Exchange (fanout: empleados_events)
                              ├──► [cola perfiles]       ──► profiles-service      → PostgreSQL perfiles
                              ├──► [cola notificaciones] ──► notifications-service → PostgreSQL notificaciones
                              └──► [cola vacaciones]     ──► vacations-service     → PostgreSQL vacaciones

Panel de administración RabbitMQ: http://localhost:15672  (admin / admin)
```

### Verificación del arranque

Desde la raíz del repositorio:

```bash
docker compose up --build
docker compose ps
docker compose logs rabbitmq
```

Cuando RabbitMQ esté listo verás en sus logs:

```
Server startup complete; 4 plugins started.
```

Y el healthcheck marcará el contenedor como `healthy`. Luego abre el panel:

```
http://localhost:15672
```

Usuario: `admin` — Contraseña: `admin`
