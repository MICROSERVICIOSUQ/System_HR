# System_HR — Sistema distribuido de gestión de recursos humanos

Siete microservicios en cuatro lenguajes que gestionan el ciclo completo de un
empleado: su alta en el catálogo organizacional, la creación y activación de su
cuenta, su perfil, sus vacaciones, los avisos que recibe y su baja. Cada servicio
tiene su propia base de datos, su propio despliegue y su propio pipeline; se
comunican por HTTP cuando la respuesta hace falta de inmediato y por eventos
cuando no.

El sistema entero se levanta con un comando y se publica al host por un único
puerto.

---

## Índice

| | |
|---|---|
| [1. Qué hace el sistema](#1-qué-hace-el-sistema) | El recorrido de un empleado |
| [2. Arquitectura](#2-arquitectura) | Puerto único, red segmentada, base por servicio |
| [3. Los siete servicios](#3-los-siete-servicios) | Tecnologías, puertos y responsabilidades |
| [4. Comunicación síncrona y resiliencia](#4-comunicación-síncrona-y-resiliencia) | Gateway, circuit breaker, fallback |
| [5. Comunicación asíncrona por eventos](#5-comunicación-asíncrona-por-eventos) | Exchange, catálogo, sobre, idempotencia |
| [6. Entrega garantizada: outbox transaccional](#6-entrega-garantizada-outbox-transaccional) | Por qué no basta publicar y esperar |
| [7. Seguridad y control de acceso](#7-seguridad-y-control-de-acceso) | JWT, RBAC y propiedad del recurso |
| [8. Ciclo de vida de la cuenta](#8-ciclo-de-vida-de-la-cuenta) | Cuatro estados y un caso borde |
| [9. Vacaciones y planificador](#9-vacaciones-y-planificador) | Transiciones por fecha |
| [10. La API](#10-la-api) | Rutas públicas y documentación OpenAPI |
| [11. Cómo ejecutar el sistema](#11-cómo-ejecutar-el-sistema) | Requisitos, arranque y accesos |
| [12. Cómo probarlo](#12-cómo-probarlo) | Postman y la pirámide de pruebas |
| [13. Observabilidad](#13-observabilidad) | Métricas, trazas y registros |
| [14. Integración continua](#14-integración-continua) | Jenkins y SonarQube |
| [15. Variables de entorno](#15-variables-de-entorno) | Configuración |
| [16. Decisiones de arquitectura](#16-decisiones-de-arquitectura) | Los catorce ADR |
| [17. Organización del repositorio](#17-organización-del-repositorio) | Dónde está cada cosa |
| [18. Estado verificado](#18-estado-verificado) | Qué se comprobó y cuándo |
| [19. Limitaciones conocidas](#19-limitaciones-conocidas) | Lo que falta y por qué |
| [20. Documentación complementaria](#20-documentación-complementaria) | Guías y READMEs por servicio |

---

## 1. Qué hace el sistema

El recorrido de un empleado atraviesa los siete servicios, y conviene leerlo
antes que cualquier diagrama, porque explica por qué el sistema está partido
donde está partido.

**Un administrador da de alta al empleado.** El servicio de empleados valida que
su correo y su número no existan, pregunta por HTTP al catálogo de departamentos
si el departamento al que lo asignan es real —esa respuesta la necesita ahora, así
que la llamada es síncrona— y guarda la fila. En la misma transacción escribe el
evento `empleado.creado`.

**A partir de ahí nadie más llama a nadie.** El evento sale a un *exchange* de
difusión y tres servicios lo recogen a la vez, sin saber unos de otros:

- **Autenticación** crea la cuenta en estado `PENDIENTE_ACTIVACION`, con rol
  `USER` y sin contraseña, y emite un token de un solo propósito para que el
  empleado establezca la suya.
- **Perfiles** proyecta un perfil con los datos que el evento trae, listo para
  que su dueño lo complete con teléfono, dirección, ciudad y biografía: datos que
  el servicio de empleados no conoce ni publica.
- **Vacaciones** crea su réplica local del empleado con el saldo de días del año.

**Notificaciones** escucha todo lo anterior y simula los correos, guardando un
historial consultable.

**El empleado establece su contraseña** con el token y ya puede autenticarse. Su
token de acceso lleva rol `USER`: puede leer el sistema y escribir sobre lo suyo
—su contraseña y su perfil—, nada más.

**Cuando se va de vacaciones, su cuenta se suspende.** No porque alguien la
suspenda, sino porque el planificador de vacaciones publica
`vacaciones.iniciadas` al llegar la fecha y autenticación reacciona. Al terminar
el período, se reactiva por el mismo camino.

**Cuando se retira, la baja es lógica y definitiva.** El registro se conserva con
su fecha de retiro para auditoría, el perfil se archiva, las vacaciones pendientes
se cancelan y la cuenta queda desactivada de forma permanente: ni el inicio de
sesión ni la recuperación de contraseña la reviven.

Todo eso ocurre sin que ningún servicio conozca a los demás. Si mañana hiciera
falta un servicio de nómina que también reaccione al alta, se suscribe al
*exchange* y nadie toca el servicio de empleados.

---

## 2. Arquitectura

```text
                            Cliente HTTP
                                 │
                      http://localhost:8080      ← el único puerto publicado
                                 │
                 ┌───────────────▼───────────────┐
                 │          API Gateway          │  valida el JWT, autoriza,
                 │     (Spring Cloud Gateway)    │  enruta y agrega
                 └───┬───────┬───────┬───────┬───┘
         ┌───────────┘       │       │       └───────────┐
         ▼                   ▼       ▼                   ▼
   ┌──────────┐      ┌──────────────┐ ┌────────────┐ ┌──────────────┐
   │   Auth   │      │  Empleados   │ │ Departa-   │ │  Perfiles ·  │
   │          │      │              │ │ mentos     │ │  Vacaciones ·│
   │          │      │   ──HTTP──►  │ │            │ │ Notificaciones│
   └────┬─────┘      └──────┬───────┘ └─────┬──────┘ └──────┬───────┘
        │                   │               │               │
        │     ┌─────────────▼───────────────▼───────────────▼─────┐
        └─────┤          RabbitMQ · exchange hr.events            │
              │                    (fanout)                       │
              └───────────────────────────────────────────────────┘
                                 │
        cada servicio con su propia base de datos PostgreSQL
```

Tres decisiones estructurales sostienen el resto del diseño.

**Un solo punto de entrada.** El gateway es el único contenedor que publica un
puerto al host; los servicios de negocio usan `expose` y las bases de datos no
publican nada. Eso no es solo orden: es lo que permite que la validación del
token y las reglas de autorización vivan en un único sitio sin dejar una puerta
trasera, porque **no hay forma de alcanzar un servicio sin pasar por el borde**.

**La red está segmentada en tres.** El gateway es el único miembro de
`frontend-gateway-net`, que es la que toca el host. Los servicios de negocio viven
en `microservices-net`. Y las bases de datos y el broker están en
`data-broker-net`, declarada `internal: true`: **sin salida a internet**, ni para
exfiltrar datos ni para descargar nada.

**Una base de datos por servicio.** Seis instancias de PostgreSQL 16
independientes, cada una con sus migraciones versionadas y su propio esquema.
Ningún servicio lee la base de otro, ni siquiera para consultar. El coste es
visible: no hay llaves foráneas entre dominios, así que «no borres un
departamento que tiene empleados» se resuelve con una pregunta HTTP en lugar de
con una restricción del motor. La ganancia también: cada servicio puede cambiar
su esquema sin coordinarse con nadie.

---

## 3. Los siete servicios

| Servicio | Tecnología | Puerto interno | Base de datos | Responsabilidad |
|---|---|---|---|---|
| **api-gateway** | Java 17 · Spring Boot · Spring Cloud Gateway | 8080 | — | Punto de entrada único: valida el JWT, autoriza, enruta y agrega |
| **auth-service** | Java 21 · Spring Boot 3.4 · jjwt · BCrypt | 8085 | `gestion_auth` | Identidad: credenciales, emisión de tokens y ciclo de vida de las cuentas |
| **employees-service** | Java 21 · Spring Boot 3.4 · JPA · Flyway · Resilience4j | 8080 | `gestion_empleados` | Fuente de verdad de los empleados y productor de los eventos de dominio |
| **departments-service** | Python 3.12 · FastAPI · SQLAlchemy · Alembic | 8081 | `gestion_departamentos` | Catálogo organizacional |
| **notifications-service** | Go 1.25 · PostgreSQL | 8082 | `gestion_notificaciones` | Consume todos los eventos, simula los correos y guarda el historial |
| **profiles-service** | Node 22 · TypeScript · Express 5 · Prisma 7 | 8083 | `gestion_perfiles` | Modelo de lectura del perfil, proyectado desde los eventos |
| **vacations-service** | Node 22 · TypeScript · Express 5 · Prisma 7 · node-cron | 8084 | `gestion_vacaciones` | Períodos de vacaciones, saldo y planificador de transiciones |

Y la infraestructura que los acompaña:

| Componente | Imagen | Puerto en el host | Para qué |
|---|---|---|---|
| RabbitMQ | `rabbitmq:management` | 15672 (consola) | Broker de eventos |
| PostgreSQL × 6 | `postgres:16` | *ninguno* | Una instancia por servicio con datos |
| Prometheus | `prom/prometheus` | 9090 | Recolección de métricas |
| Grafana | `grafana/grafana` | 3000 | Tableros y alertas |
| Loki + Promtail | `grafana/loki`, `grafana/promtail` | 3100 | Registros centralizados |
| Zipkin | `openzipkin/zipkin` | 9411 | Trazas distribuidas |
| Jenkins | imagen propia | 8081 | Integración continua *(perfil `ci`)* |
| SonarQube | `sonarqube:community` | 9000 | Análisis de calidad *(perfil `ci`)* |

El gateway y el servicio de empleados comparten el puerto interno 8080 sin
conflicto, porque son contenedores distintos. Jenkins y SonarQube están en el
perfil `ci` y **no arrancan** con un `docker compose up` normal.

---

## 4. Comunicación síncrona y resiliencia

Una sola llamada entre servicios es síncrona: la que hace el servicio de
empleados al catálogo de departamentos para validar que el departamento existe
antes de guardar un empleado. Es síncrona porque la respuesta decide si la
petición del cliente se acepta o se rechaza, y eso no se puede responder más
tarde.

Por eso mismo es la llamada más frágil del sistema, y está protegida con
Resilience4j.

| Parámetro | Valor |
|---|---|
| Circuito | `departmentsCircuitBreaker` |
| Ventana deslizante | Últimas 3 operaciones |
| Umbral de apertura | 100 % de fallos: tres operaciones consecutivas |
| Espera en `OPEN` | 30 segundos |
| Pruebas en `HALF_OPEN` | 1 |
| Intentos por operación | 4, con esperas de 1, 2 y 4 segundos |
| Tiempos de espera HTTP | 3 s para conectar, 5 s para leer |

Una operación cuenta como fallo **después** de agotar sus reintentos, de modo que
un error de red aislado no abre el circuito. Con el circuito abierto, la consulta
se rechaza sin salir a la red: la respuesta pasa de unos 19 segundos —cuatro
intentos con sus esperas— a unos 10 milisegundos. Treinta segundos más tarde entra
una sola operación de prueba; si tiene éxito, el circuito cierra.

**Qué pasa cuando el catálogo no responde.** El alta devuelve `503` y no guarda
nada:

```json
{ "message": "El servicio de departamentos no está disponible temporalmente" }
```

Se prioriza la consistencia sobre la disponibilidad: un empleado cuyo
departamento no se pudo validar sería un dato que alguien tendría que arreglar a
mano más tarde. No se crean registros pendientes y, por lo mismo, no hace falta
reconciliarlos. Las consultas que no dependen del catálogo siguen funcionando.

**Un departamento inexistente no es una caída.** El catálogo responde `404`, que
es una comunicación exitosa; el servicio de empleados lo traduce en `400` por
regla de negocio incumplida y **no lo cuenta como fallo del circuito**. Confundir
las dos cosas abriría el circuito por un error del cliente.

El estado del circuito y sus contadores se consultan en
`GET /empleados/circuit-breaker`, y cada transición queda en el registro.

---

## 5. Comunicación asíncrona por eventos

Todo lo demás viaja por RabbitMQ. El *exchange* es de tipo **fanout**: duplica
cada mensaje hacia todas las colas suscritas y la *routing key* siempre va vacía.

| Exchange | Tipo | Durabilidad |
|---|---|---|
| `hr.events` | Fanout | Durable |

| Cola | Consumidor |
|---|---|
| `notifications.queue` | notifications-service |
| `profiles.queue` | profiles-service |
| `vacations.queue` | vacations-service |
| `auth.queue` | auth-service |

Un *exchange* de difusión en lugar de uno por tema es una decisión deliberada: el
productor no declara quién debe enterarse. Añadir un consumidor nuevo es
suscribir una cola, sin tocar al productor ni su configuración.

### Catálogo de eventos

| Evento | Productor | Consumidores |
|---|---|---|
| `empleado.creado` | employees | auth · profiles · vacations · notifications |
| `empleado.actualizado` | employees | profiles · vacations · notifications |
| `empleado.retirado` | employees | auth · profiles · vacations · notifications |
| `vacaciones.programadas` | vacations | notifications |
| `vacaciones.iniciadas` | vacations *(planificador)* | auth · notifications |
| `vacaciones.finalizadas` | vacations *(planificador)* | auth · notifications |
| `usuario.creado` | auth | notifications |
| `usuario.recuperacion` | auth | notifications |
| `cuenta.activada` | auth | notifications |
| `cuenta.desactivada` | auth | notifications |

### El sobre del evento

Todos los mensajes comparten la misma envoltura, descrita en
[ADR-005](docs/decisions/ADR-005-event-driven-architecture.md) y en el README de
cada productor:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-28T20:00:00Z",
  "producer": "employees-service",
  "data": { "id": "E001", "nombre": "Juan", "email": "juan@empresa.com" }
}
```

El campo `id` no es decorativo: es la **clave de idempotencia**. RabbitMQ
garantiza entrega al menos una vez, así que un mensaje puede llegar dos veces
—una reconexión, un reinicio del consumidor entre el procesamiento y el ACK— y
todos los consumidores guardan el último identificador procesado con un índice
único. Un evento repetido se descarta, se registra como `[DUPLICADO]` y se
confirma con ACK, porque reencolarlo solo repetiría el ciclo.

---

## 6. Entrega garantizada: outbox transaccional

Guardar un cambio y publicar su evento son dos operaciones sobre sistemas
distintos, y eso significa que pueden separarse.

El problema concreto: el alta de un empleado guardaba la fila y después publicaba
el evento dentro de un `try/catch` que solo registraba el fallo. Si el broker no
estaba disponible en ese instante, el empleado quedaba creado y `empleado.creado`
**no llegaba nunca**. Nadie reintentaba. Autenticación no creaba la cuenta,
perfiles no creaba el perfil y vacaciones no creaba el saldo: una inconsistencia
permanente que se descubría más tarde y se arreglaba a mano.

La solución es el patrón **outbox**, implementado en los tres productores
—empleados, autenticación y vacaciones—:

1. El evento se escribe en una tabla `outbox_evento` **dentro de la misma
   transacción** que el cambio de negocio. O se guardan los dos o ninguno: es la
   misma base de datos, así que no hace falta una transacción distribuida.
2. Un publicador periódico lee lo pendiente con `FOR UPDATE SKIP LOCKED`, lo
   envía al broker y marca cada fila al confirmarse el envío.
3. Si el broker está caído, las filas se quedan, se cuentan los intentos y se
   reintenta solo en el ciclo siguiente.

Esto garantiza entrega **al menos una vez**, no exactamente una vez: un corte
entre el envío y la marca reenvía el evento. No hace falta más, porque los
consumidores ya son idempotentes.

Dos detalles que importan en la práctica:

- **Un fallo de un evento concreto no interrumpe el ciclo, pero un fallo del
  broker sí.** Un evento con el contenido corrupto fallaría siempre, y cortar por
  él atascaría a todos los que vienen detrás; un broker caído, en cambio, hará
  fallar a todos, así que insistir solo llena el registro.
- **El outbox de autenticación guarda su contenido cifrado** con AES-GCM y clave
  derivada del secreto de firma, porque sus eventos llevan tokens de un solo uso.
  Al publicarse, el contenido se vacía.

El efecto visible es que **el sistema acepta escrituras con RabbitMQ apagado**:
`POST /auth/recover-password` responde `202` y `POST /vacaciones` responde `201`
sin broker, y los eventos salen solos cuando vuelve.

Registrado en [ADR-014](docs/decisions/ADR-014-outbox-transaccional.md).

---

## 7. Seguridad y control de acceso

**La validación del token y las reglas de autorización viven solo en el
gateway.** Registrado en
[ADR-008](docs/decisions/ADR-008-validacion-jwt-en-el-gateway.md).

```text
Cliente ──Bearer JWT──► api-gateway ──X-Auth-User / X-Auth-Role──► microservicio
                             │
                   1. JwtAuthenticationFilter → firma, emisor, exp, type → 401
                   2. AuthorizationFilter      → rol y propiedad          → 403
```

El sistema tiene siete componentes en cuatro lenguajes. Validar en cada servicio
obligaría a escribir la misma lógica cuatro veces con librerías distintas y a
mantener sincronizados el criterio de expiración, el tratamiento del claim `type`
y la distinción entre 401 y 403. Cada servicio nuevo añadiría otra copia. Y el
día que la validación pase a JWKS contra un proveedor de identidad externo, el
cambio se hace en un sitio en lugar de en seis.

**Cómo se propaga la identidad.** Tras validar, el gateway añade `X-Auth-User`
(el claim `sub`) y `X-Auth-Role` (el claim `role`). Los servicios confían en esas
cabeceras, y es seguro por dos razones concretas:

1. **El gateway borra esas cabeceras si vienen del cliente**, antes de validar.
   Sin eso, cualquiera enviaría `X-Auth-Role: ADMIN`. Hay una prueba automatizada
   para cada una.
2. **Solo el gateway está publicado al host.** No hay manera de esquivar el borde.

### Los dos tipos de token

| | Token de acceso | Token de restablecimiento |
|---|---|---|
| Lo emite | `POST /auth/login` | El alta de un empleado, `/auth/recover-password` o `/auth/internal/tokens/restablecimiento` |
| Claims | `sub`, `role`, `iss`, `iat`, `exp` | `sub`, `type: RESET_PASSWORD`, `iss`, `iat`, `exp` |
| Sirve para | Cualquier recurso autorizado | Solo establecer una contraseña |

Los dos se firman con el mismo secreto, así que **la firma no los distingue**: lo
hace el claim `type`. La validación va en las dos direcciones —un token de acceso
no cambia una contraseña y un token de restablecimiento no abre ningún recurso— y
hay una prueba para cada sentido.

La contraseña nunca entra en el token y en base de datos se guarda como hash
BCrypt con sal propia.

### Reglas de autorización

| Situación | Resultado |
|---|---|
| Sin cabecera `Authorization`, o token inválido, expirado o alterado | **401** |
| Rol `ADMIN`, cualquier método y ruta | Permitido |
| Rol `USER`, método de lectura (`GET`, `HEAD`, `OPTIONS`) | Permitido |
| Rol `USER`, `POST /auth/change-password` | Permitido: su propia contraseña |
| Rol `USER`, `PUT`/`PATCH /perfiles/{id}` con `id == sub` | Permitido: su propio perfil |
| Rol `USER`, cualquier otra escritura | **403** |

Las rutas públicas son las imprescindibles para conseguir un token, más salud,
métricas y documentación: `/auth/login`, `/auth/recover-password`,
`/auth/reset-password`, `/health`, `/metrics`, `/actuator/**` y las de Swagger.

`/auth/reset-password` es pública **a propósito**: su autorización no viene de una
sesión previa, sino del token de un solo propósito que llega en el cuerpo. Exigir
un token de acceso ahí haría imposible la activación inicial, que es justo el
momento en el que el usuario todavía no puede iniciar sesión.

### Autorización por propiedad del recurso

El control por rol no alcanza para el perfil: un `USER` no puede escribir en
general, pero sí sobre lo suyo.

```text
si rol == ADMIN                                   → permitir
si rol == USER y el método es de lectura           → permitir
si rol == USER y recurso.empleadoId == token.sub   → permitir
en cualquier otro caso                             → 403
```

```bash
# Con el token de E601
curl -X PUT http://localhost:8080/perfiles/E601 -H "Authorization: Bearer $TOKEN"  # 200
curl -X PUT http://localhost:8080/perfiles/E602 -H "Authorization: Bearer $TOKEN"  # 403
```

Mismo token, mismo verbo, misma ruta salvo el identificador. La diferencia es
quién es dueño del dato: es el paso del control por rol al control por atributos,
y es lo que permite que cada empleado gestione su perfil sin darle permisos de
administrador.

Por eso el claim `sub` es el identificador del empleado y no su correo: el correo
es la credencial de entrada y podría cambiar sin que los permisos ya concedidos
se rompan.

---

## 8. Ciclo de vida de la cuenta

Ningún servicio llama a autenticación para cambiar el estado de una cuenta. El
estado cambia por eventos.

```text
         empleado.creado                 reset-password
   ─────────────────────────► PENDIENTE ──────────────────► ACTIVA
                             ACTIVACIÓN                      │  ▲
                                                             │  │
                                   vacaciones.iniciadas      │  │ vacaciones.
                                                             ▼  │ finalizadas
                                                       SUSPENDIDA
                                                        TEMPORAL
                                                             │
                              empleado.retirado             │
   ──────────────────────────────────────────────────────────┴──► DESACTIVADA
                                                                  PERMANENTE
```

| Estado | Cómo se llega | ¿Inicia sesión? | ¿Se reactiva al terminar las vacaciones? |
|---|---|---|---|
| `PENDIENTE_ACTIVACION` | `empleado.creado` | No | No |
| `ACTIVA` | `reset-password` o fin de vacaciones | **Sí** | — |
| `SUSPENDIDA_TEMPORAL` | `vacaciones.iniciadas` | No | **Sí** |
| `DESACTIVADA_PERMANENTE` | `empleado.retirado` | No | **No** |

### El caso borde que explica por qué el estado es una enumeración

Si un empleado se retira **mientras está de vacaciones**, al llegar la fecha de
regreso el planificador publica `vacaciones.finalizadas` sobre una cuenta que ya
tiene baja permanente. La cuenta **no se reactiva**, y la garantía está en el
modelo, no en una condición suelta:

```java
public boolean esReactivablePorVacaciones() {
    return this == SUSPENDIDA_TEMPORAL;
}
```

Con un booleano `activa` esto sería imposible: el manejador no podría distinguir
si el `false` vino de unas vacaciones o de un retiro. Registrado en
[ADR-009](docs/decisions/ADR-009-estados-de-cuenta.md) y cubierto por una prueba
unitaria y un escenario de la suite de extremo a extremo.

---

## 9. Vacaciones y planificador

| | |
|---|---|
| Mecanismo | `node-cron` dentro del servicio de vacaciones |
| Frecuencia | `VACACIONES_CRON`, por omisión cada minuto |
| Interruptor | `VACACIONES_SCHEDULER_ENABLED` |

Cada ciclo hace dos barridos: los períodos `PROGRAMADA` cuya fecha de inicio ya
llegó pasan a `EN_CURSO` y publican `vacaciones.iniciadas`; los `EN_CURSO` cuya
fecha de fin ya pasó quedan `FINALIZADA` y publican `vacaciones.finalizadas`.

**`fechaFin` es el día de regreso**, no el último día de descanso: un día de
vacaciones son dos fechas consecutivas, y por eso el servicio exige que la fecha
de fin sea estrictamente posterior a la de inicio.

Cada transición se hace con un *compare-and-set* en base de datos
(`UPDATE ... WHERE id = ? AND estado = ?`): si dos ciclos se solapan, el segundo
afecta cero filas y no vuelve a publicar nada.

**Cómo demostrar el flujo sin esperar días.** Hay dos endpoints de desarrollo,
reservados al rol `ADMIN`, que ejecutan la misma transición y publican el mismo
evento que el planificador:

```bash
curl -X POST http://localhost:8080/vacaciones/{id}/forzar-inicio -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8080/vacaciones/{id}/forzar-fin    -H "Authorization: Bearer $TOKEN"
```

Las pruebas automatizadas los usan por la misma razón: provocan la transición sin
acoplarse a la frecuencia del cron.

---

## 10. La API

Base única: `http://localhost:8080`.

| Prefijo | Destino | Qué expone |
|---|---|---|
| `/auth/**` | auth-service | Inicio de sesión, recuperación, establecimiento y cambio de contraseña |
| `/empleados/**` | employees-service | CRUD de empleados, auditoría de retiros y estado del circuito |
| `/departamentos/**` | departments-service | CRUD del catálogo organizacional |
| `/perfiles/**` | profiles-service | Lectura y edición de perfiles |
| `/vacaciones/**` | vacations-service | Períodos, saldos y disparo manual de transiciones |
| `/notificaciones/**` | notifications-service | Historial de avisos, solo lectura |
| `/empleados/{id}/completo` | *el propio gateway* | Vista agregada de empleado, perfil y vacaciones |
| `/health`, `/metrics` | gateway y cada servicio | Sonda ligera y métricas, sin token |

La sonda detallada de cada servicio —con un check por dependencia y `503` si
alguna falla— vive en la raíz del contenedor y **no está enrutada al exterior**:
la consulta Docker desde dentro, que es quien decide si reiniciarlo. Lo que el
gateway publica es la sonda ligera, que responde si el servicio atiende
peticiones.

Las rutas en inglés (`/profiles`, `/vacations`, `/notifications`) siguen
enrutándose como alias, porque fueron públicas antes del cambio de nomenclatura.

### La vista agregada

`GET /empleados/{id}/completo` lo atiende un controlador del propio gateway, que
consulta los tres servicios **en paralelo** y compone una respuesta. El empleado
es obligatorio: sin él responde `404`. El perfil y el saldo de vacaciones son
opcionales y degradan a `null` si su servicio no responde, cada uno con su propio
circuito. Un `404` de una dependencia opcional significa «no existe», no «no pude
preguntar», y se distingue del `5xx`.

### Documentación OpenAPI

Cada servicio publica su contrato y declara el esquema `bearerAuth`, así que se
puede pegar el token en el botón **Authorize** de Swagger y probar la API sin
Postman. El gateway los agrega en una sola interfaz con un desplegable por
servicio:

| Interfaz | URL |
|---|---|
| **Swagger UI agregada** | http://localhost:8080/swagger-ui.html |
| Contratos individuales | `/v3/api-docs/{auth,empleados,departamentos,perfiles,vacaciones,notificaciones}` |

---

## 11. Cómo ejecutar el sistema

### Requisitos

- **Docker Desktop** con Compose v2 (usa `include` y `profiles`).
- Nada más: todos los lenguajes y herramientas se compilan dentro de imágenes.

Para ejecutar las pruebas fuera de contenedor hacen falta además Java 21, Node 22,
Python 3.12 y Go 1.25, según la suite.

### Configuración

```bash
cp .env.example .env
```

`JWT_SECRET` es obligatorio y necesita **al menos 32 bytes**: tanto autenticación
como el gateway abortan el arranque si es más corto, en lugar de firmar con una
clave débil. Compose lo declara con `${JWT_SECRET:?...}`, de modo que si falta el
levantamiento falla con un mensaje claro en vez de arrancar con un valor conocido.

El script `scripts/setup-secrets.sh` rellena las claves vacías del `.env` con
valores generados, sin tocar las que ya tengan valor.

### Arranque

```bash
docker compose up -d --build
```

```bash
docker compose ps
```

Levanta **19 contenedores**: los siete servicios, las seis bases de datos, el
broker y los cinco de observabilidad. El arranque está ordenado por
`depends_on: service_healthy`, así que el gateway no acepta tráfico hasta que sus
dependencias responden.

```bash
docker compose down
```

`down` conserva los volúmenes; añadir `-v` los borra y obliga a volver a aplicar
todas las migraciones desde cero.

Si el puerto 8080 está ocupado en su máquina —en Windows suele estarlo por
Apache—, levante con otro:

```bash
GATEWAY_PORT=8090 docker compose up -d
```

### Accesos

| Recurso | URL | Credenciales |
|---|---|---|
| API | http://localhost:8080 | Token del inicio de sesión |
| Swagger UI agregada | http://localhost:8080/swagger-ui.html | — |
| Consola de RabbitMQ | http://localhost:15672 | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` |
| Grafana | http://localhost:3000 | `GRAFANA_ADMIN_*` |
| Prometheus | http://localhost:9090 | — |
| Zipkin | http://localhost:9411 | — |

### El primer token

El sistema crea un **administrador semilla** al arrancar, configurable con
`AUTH_SEED_ADMIN_*`. Sin él no habría forma de empezar, porque las cuentas de
empleado nacen con rol `USER` desde el evento `empleado.creado`.

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"admin@empresa.com","password":"Admin12345"}'
```

```powershell
$token = (Invoke-RestMethod -Uri http://localhost:8080/auth/login -Method POST `
  -ContentType "application/json" `
  -Body '{"usuario":"admin@empresa.com","password":"Admin12345"}').token

Invoke-RestMethod -Uri http://localhost:8080/empleados -Headers @{ Authorization = "Bearer $token" }
```

---

## 12. Cómo probarlo

### Un recorrido completo con Postman

La colección **[`postman/flujo-completo.postman_collection.json`](postman/flujo-completo.postman_collection.json)**
recorre el sistema entero en el orden en el que ocurren las cosas, con las
carpetas y las peticiones numeradas y nombradas por lo que hace cada endpoint:

| Carpeta | Qué recorre |
|---|---|
| `00 Preparación` | Inicio de sesión del administrador y datos únicos de la ejecución |
| `01 Catálogo de departamentos` | CRUD, duplicados, validación y paginación |
| `02 Alta de empleados` | Registro, unicidad, departamento inexistente y estado del circuito |
| `03 Activación de la cuenta` | Token de activación, política de contraseñas, inicio de sesión y cambio |
| `04 Perfil del empleado` | Proyección por evento, datos personales y propiedad del recurso |
| `05 Vacaciones y suspensión` | Validaciones, solapamiento, inicio y fin forzados, suspensión y reactivación |
| `06 Historial de notificaciones` | Historial, filtrado y ausencia de tokens guardados |
| `07 Vista agregada` | Composición de tres servicios y degradación |
| `08 Seguridad` | 401, 403, cabeceras falsificadas y tipos de token |
| `09 Observabilidad` | Salud, métricas y contratos |
| `10 Baja del empleado` | Retiro lógico, archivado, desactivación y auditoría |

```bash
npx newman run postman/flujo-completo.postman_collection.json \
  -e postman/system-hr-flujo-completo.postman_environment.json
```

Son 75 peticiones y **96 aserciones** en unos doce segundos. Si el gateway no
está en el 8080, basta añadir `--env-var "gatewayBaseUrl=http://localhost:8090"`.

Dos cosas la hacen repetible y fiable:

- **Cada ejecución inventa sus propios identificadores** a partir del reloj. Una
  colección con datos fijos solo pasa la primera vez.
- **Los efectos asíncronos se esperan comprobando una condición**, con un tope de
  30 segundos, nunca con una pausa fija. Una pausa fija o sobra o no alcanza, y el
  día que el evento tarda un poco más la prueba falla sin motivo.

En [`postman/`](postman/) hay además colecciones centradas en un servicio o en un
aspecto concreto —seguridad, ciclo de vida de la cuenta, listados paginados,
observabilidad— útiles para depurar una parte sin recorrer todo.

### La pirámide de pruebas

```text
                    ┌─────────────────────────┐
                    │   BDD / E2E  (39)       │  Flujos de negocio completos
                    │   e2e-tests/            │  Necesita el sistema arriba
                    ├─────────────────────────┤
                 ┌──┤  Integración            │  Que el esquema exista de verdad
                 │  │  Testcontainers         │  Necesita Docker
              ┌──┴──┴─────────────────────────┤
              │     Unitarias (cuatro         │  Reglas de negocio
              │     lenguajes)                │  Solo el runtime del lenguaje
              └───────────────────────────────┘
```

Los tres niveles verifican cosas distintas y fallan por razones distintas: eso es
lo que permite que un fallo señale su propia causa.

- **Unitarias.** Reglas de negocio con todas las dependencias sustituidas por
  dobles. Ninguna abre un socket ni necesita Docker. Corren en segundos.
- **Integración.** Lo único que el nivel inferior no puede verificar: que las
  migraciones se aplicaron y que las restricciones `UNIQUE` y `NOT NULL` están **en
  el esquema de PostgreSQL** y no solo en el código. Levantan la base real con
  Testcontainers, y se omiten limpiamente donde no hay Docker en lugar de fallar
  en rojo.
- **BDD / extremo a extremo.** Flujos completos a través del gateway, incluidos
  los que atraviesan RabbitMQ y varios servicios.

Dos consecuencias de diseño que merecen mención:

1. **El predicado de solapamiento de vacaciones se extrajo a un módulo puro.**
   Vivía dentro de una consulta de Prisma, donde solo se podía verificar con una
   base de datos real. Ahora la consulta filtra por empleado y estado y el
   predicado se aplica en memoria, de modo que la prueba unitaria cubre el código
   que de verdad corre.
2. **El doble no puede contradecir a producción.** El repositorio falso resuelve
   los solapamientos reutilizando la misma función que el repositorio real. Un
   doble con su propia copia de la regla puede quedar en verde mientras producción
   está roto.

El razonamiento completo está en
[ADR-010](docs/decisions/ADR-010-piramide-de-pruebas.md).

### Comandos por suite

Cada comando devuelve código de salida distinto de cero si algo falla, y las
rutas de los reportes son fijas: son el contrato que consume Jenkins.

| Suite | Comando | Reportes |
|---|---|---|
| employees-service | `cd services/employees-service && ./mvnw verify` | `target/surefire-reports/`, `target/site/jacoco/jacoco.xml` |
| auth-service | `cd services/auth-service && ./mvnw verify` | `target/surefire-reports/`, `target/site/jacoco/jacoco.xml` |
| api-gateway | `cd api-gateway && ./mvnw verify` | `target/surefire-reports/`, `target/site/jacoco/jacoco.xml` |
| departments-service | `cd services/departments-service && pytest` | `reports/junit.xml`, `coverage.xml` |
| profiles-service | `cd services/profiles-service && npm test` | `reports/junit.xml`, `reports/lcov.info` |
| vacations-service | `cd services/vacations-service && npm test` | `reports/junit.xml`, `reports/lcov.info` |
| notifications-service | `cd services/notifications-service && go test ./...` | `reports/` |
| BDD de extremo a extremo | `cd e2e-tests && ./mvnw test` | `reports/cucumber.html`, `reports/junit.xml` |

La suite de extremo a extremo necesita el sistema arriba. También puede correr en
contenedor, con sus reportes montados en el host:

```bash
docker compose --profile testing run --build --rm bdd-tests
```

El perfil `testing` la mantiene fuera del `docker compose up` habitual.

### Los escenarios están escritos en español

Un escenario en Gherkin lo puede leer y corregir alguien que no programa. Eso es
lo que hace posible acordar el comportamiento antes de escribir el código, y lo
que convierte la suite en documentación que no se queda obsoleta: si el
`.feature` miente, la suite se pone roja.

```gherkin
# language: es
Característica: Offboarding de empleados

  Escenario: Desvinculación completa de un empleado
    Dado que el empleado puede iniciar sesión
    Cuando retiro al empleado
    Entonces la respuesta debe tener código 200
    Y eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
    Y el motivo de la desactivación debe ser "RETIRO"
    Y eventualmente el empleado no puede iniciar sesión
    Y el mensaje de error debe contener "permanente"
```

Los pasos que empiezan por **«eventualmente»** son los que atraviesan el broker:
se verifican con reintentos y no con una espera fija, porque una espera fija paga
siempre su peor caso y además falla de forma intermitente el día que el evento
tarda un poco más. Registrado en
[ADR-011](docs/decisions/ADR-011-verificacion-de-efectos-asincronos.md).

El detalle de la suite está en [`e2e-tests/README.md`](e2e-tests/README.md).

### Cobertura

Cada servicio aplica su propio umbral del 70 % con la herramienta de su lenguaje,
y el umbral **rompe el comando**, no solo informa:

| Servicio | Herramienta | Dónde se exige |
|---|---|---|
| employees | JaCoCo | `check` en el `pom.xml`, rompe `verify` |
| departments | `pytest-cov` | `--cov-fail-under=70` en `pytest.ini` |
| profiles · vacations | `node:test` | `--test-coverage-lines=70` en `npm test` |
| notifications | `go tool cover` | El pipeline corta por debajo del 70 % |
| auth · api-gateway | — | El Quality Gate `RRHH-70` de SonarQube |

---

## 13. Observabilidad

Los tres pilares, con un contenedor por pilar y Grafana al frente.

**Métricas.** Los siete servicios exponen `/metrics` en formato Prometheus, y los
tres servicios Java conservan además `/actuator/prometheus` para no romper a quien
ya la usaba. Prometheus recoge ocho objetivos —los siete servicios y él mismo— y
Grafana los presenta en tableros aprovisionados desde archivos versionados.

**Trazas.** Los siete propagan el `traceparent` del estándar W3C Trace Context y
reportan a Zipkin, incluidos los saltos asíncronos: el publicador del outbox
continúa la traza que originó el evento, de modo que una petición HTTP y la
notificación que provoca minutos después aparecen en la misma traza.

**Registros.** Promtail recoge la salida de los contenedores y la envía a Loki.
Los servicios Java escriben en JSON con un campo `context` estructurado, de modo
que `eventId`, `empleadoId` o `eventType` son campos consultables y no texto
dentro de un mensaje.

---

## 14. Integración continua

Jenkins y SonarQube se levantan con el perfil `ci`:

```bash
python3 ci/preparar-entorno.py
docker compose --profile ci up -d --build
```

Jenkins queda en http://localhost:8081 y SonarQube en http://localhost:9000. Las
contraseñas generadas quedan en `.env`. No se usa el asistente inicial de
Jenkins: la configuración como código crea el administrador, las credenciales y
los trabajos.

**Hay un pipeline por servicio, y los siete existen como trabajo.** Cada
`Jenkinsfile` vive junto al código que construye:

| Etapa | Qué hace |
|---|---|
| Preparación | Instala dependencias y genera clientes (Prisma, migraciones) |
| Pruebas | Ejecuta la suite del servicio y publica resultados y cobertura |
| Análisis | Envía el código y la cobertura a SonarQube |
| Quality Gate | Espera el veredicto y **detiene el pipeline** si no pasa |
| Empaquetado | Construye la imagen Docker |

El Quality Gate `RRHH-70` exige un 70 % de cobertura y se aplica a los siete
proyectos. El alcance del análisis está alineado con el de las pruebas: incluir
migraciones, pruebas o entornos virtuales en `sonar.sources` hunde la cobertura
medida aunque las pruebas estén bien.

Jenkins consulta cambios en el repositorio indicado por `CI_REPOSITORY_URL` y
`CI_REPOSITORY_BRANCH`.

> [!NOTE]
> El CI y el sistema completo compiten por la misma memoria. En un equipo con
> poca RAM conviene no tenerlos arriba a la vez: `docker compose --profile ci
> stop` antes de levantar la aplicación.

---

## 15. Variables de entorno

Todas están documentadas en `.env.example` y ninguna credencial está escrita en
el código.

| Variable | Por omisión | Para qué |
|---|---|---|
| `GATEWAY_PORT` | `8080` | Único puerto publicado al host |
| `JWT_SECRET` | *(obligatoria)* | Secreto HS256 compartido. Mínimo 32 bytes |
| `JWT_ISSUER` | `system-hr-auth` | Claim `iss`, verificado al validar |
| `JWT_ACCESS_TTL_SECONDS` | `3600` | Vigencia del token de acceso |
| `JWT_RESET_TTL_SECONDS` | `3600` | Vigencia del token de restablecimiento |
| `AUTH_SEED_ADMIN_EMAIL` | `admin@empresa.com` | Correo del administrador semilla |
| `AUTH_SEED_ADMIN_PASSWORD` | `Admin12345` | Su contraseña inicial |
| `VACACIONES_CRON` | `* * * * *` | Frecuencia del planificador |
| `VACACIONES_SCHEDULER_ENABLED` | `true` | Interruptor del planificador |
| `OTEL_EXPORTER_ZIPKIN_ENDPOINT` | `http://zipkin:9411/api/v2/spans` | Destino de las trazas |
| `PROMETHEUS_PORT`, `GRAFANA_PORT`, `LOKI_PORT`, `ZIPKIN_PORT` | `9090`, `3000`, `3100`, `9411` | Puertos de observabilidad |
| `CI_REPOSITORY_URL`, `CI_REPOSITORY_BRANCH` | — | Origen que leen los pipelines |

Además, cada servicio tiene su bloque `*_DB_*` con las credenciales de su propia
base de datos.

---

## 16. Decisiones de arquitectura

Las decisiones con alternativas descartadas y consecuencias están en
[`docs/decisions/`](docs/decisions/):

| ADR | Decisión |
|---|---|
| [001](docs/decisions/ADR-001-motor-de-base-de-datos.md) | PostgreSQL en instancias separadas por servicio |
| [002](docs/decisions/ADR-002-estrategia-de-esquema.md) | Migraciones versionadas: Flyway, Alembic y Prisma Migrate |
| [003](docs/decisions/ADR-003-garantia-de-unicidad.md) | Unicidad en dos capas: consulta previa y restricción `UNIQUE` |
| [004](docs/decisions/ADR-004-rabbitmq-message-broker.md) | RabbitMQ como broker de eventos |
| [005](docs/decisions/ADR-005-event-driven-architecture.md) | Arquitectura orientada a eventos |
| [006](docs/decisions/ADR-006-fanout-exchange.md) | Exchange *fanout* `hr.events` para distribución 1 a N |
| [007](docs/decisions/ADR-007-idempotent-consumers.md) | Consumidores idempotentes por identificador de evento |
| [008](docs/decisions/ADR-008-validacion-jwt-en-el-gateway.md) | Validación del JWT y autorización en el gateway |
| [009](docs/decisions/ADR-009-estados-de-cuenta.md) | El estado de la cuenta como enumeración, no booleano |
| [010](docs/decisions/ADR-010-piramide-de-pruebas.md) | Pirámide de pruebas y reparto de responsabilidades |
| [011](docs/decisions/ADR-011-verificacion-de-efectos-asincronos.md) | Verificación de efectos asíncronos con reintentos |
| [012](docs/decisions/ADR-012-departments-en-python.md) | Reconstrucción del catálogo de departamentos en Python |
| [013](docs/decisions/ADR-013-renombrar-campos-publicados.md) | Cómo renombrar un campo ya publicado sin romper clientes |
| [014](docs/decisions/ADR-014-outbox-transaccional.md) | Outbox transaccional para la entrega de eventos |

---

## 17. Organización del repositorio

```text
System_HR/
├── api-gateway/              Punto de entrada: enrutamiento, seguridad y agregación
├── services/
│   ├── auth-service/         Identidad y ciclo de vida de las cuentas
│   ├── employees-service/    Fuente de verdad de empleados
│   ├── departments-service/  Catálogo organizacional
│   ├── notifications-service/ Historial de avisos
│   ├── profiles-service/     Modelo de lectura del perfil
│   └── vacations-service/    Vacaciones y planificador
├── e2e-tests/                Suite BDD en Gherkin español
├── infrastructure/
│   ├── databases/            Las seis bases y el broker
│   └── rabbitmq/             Exchange, colas y enlaces
├── observability/
│   ├── prometheus/           Objetivos y reglas
│   ├── grafana/              Tableros y fuentes aprovisionadas
│   ├── loki/                 Almacén de registros
│   └── promtail/             Recolección de la salida de los contenedores
├── jenkins/                  Configuración como código del CI
├── ci/                       Scripts de preparación de Jenkins y SonarQube
├── postman/                  Colecciones, entornos y sus generadores
├── scripts/                  Utilidades de operación
├── tests/                    Scripts de humo y validación de resiliencia
├── docs/
│   ├── decisions/            Los catorce ADR
│   ├── architecture/         Diagramas
│   └── evidencias/           Salidas capturadas de las verificaciones
├── docker-compose.yml
└── .env.example
```

Cada servicio tiene su propio `README.md` con el detalle de su dominio, su
esquema y sus endpoints.

---

## 18. Estado verificado

Lo que sigue son resultados capturados, no objetivos.

**Los siete pipelines, con el Quality Gate aplicado:**

| Pipeline | Resultado | Cobertura en SonarQube |
|---|---|---|
| api-gateway | SUCCESS | 74,9 % |
| auth-service | SUCCESS | 72,5 % |
| employees-service | SUCCESS | 81,8 % |
| departments-service | SUCCESS | 80,2 % |
| notifications-service | SUCCESS | 79,6 % |
| profiles-service | SUCCESS | 92,8 % |
| vacations-service | SUCCESS | 72,2 % |

**El resto de la verificación:**

| Comprobación | Resultado |
|---|---|
| Escenarios BDD de extremo a extremo | **39 / 39** |
| Objetivos de Prometheus en `/metrics` | 8 de 8, todos UP |
| Servicios visibles en Zipkin | 7 de 7 |
| Outbox con el broker caído | `202` y `201` sin broker; entrega automática al volver |
| Contenido cifrado del outbox de autenticación | Sin tokens legibles; se vacía al publicar |
| Recorrido completo en Postman | **96 / 96 aserciones**, dos ejecuciones seguidas sobre la misma base de datos |
| Colecciones por servicio | Verdes: autenticación 47/47, perfiles 38/38, empleados 38/38, departamentos 24/24 |
| Umbral de cobertura del 70 % | Aplicado en los siete, rompe el comando |

El detalle de cada comprobación, con los comandos y las salidas, está en
[`docs/ESTADO-AUDITORIA.md`](docs/ESTADO-AUDITORIA.md) y en
[`docs/evidencias/`](docs/evidencias/).

---

## 19. Limitaciones conocidas

Están aquí porque conocerlas es parte del diseño, no porque se hayan pasado por
alto.

- **El planificador de vacaciones asume una sola instancia.** Se ejecuta en cada
  instancia del servicio, así que con N instancias las N dispararían el mismo
  evento. La deduplicación del consumidor mitiga el daño pero no evita el trabajo
  duplicado; la solución correcta es un *lock* distribuido en base de datos. El
  *compare-and-set* del repositorio protege dentro de un proceso, no entre
  procesos.
- **El outbox no se purga.** Las filas publicadas se conservan indefinidamente.
  Hace falta un trabajo de limpieza antes de que el volumen importe.
- **Rotar `JWT_SECRET` con filas pendientes en el outbox de autenticación las
  vuelve indescifrables**, porque la clave de cifrado se deriva de ese secreto.
  Conviene vaciar la cola antes de rotarlo.
- **La firma es simétrica (HS256) con un secreto compartido.** En producción iría
  una firma asimétrica RS256 con validación por JWKS, que además permitiría que el
  gateway verificara sin poder firmar.
- **Las imágenes se construyen localmente.** No hay registro ni despliegue
  automático: el pipeline termina en la imagen construida.

---

## 20. Documentación complementaria

| Documento | Contenido |
|---|---|
| [`docs/ESTADO-AUDITORIA.md`](docs/ESTADO-AUDITORIA.md) | Estado de la verificación, defectos encontrados y cómo se cerraron |
| [`docs/decisions/`](docs/decisions/) | Los catorce ADR |
| [`e2e-tests/README.md`](e2e-tests/README.md) | La suite BDD: el «World», los escenarios y cómo añadir uno |
| [`docs/architecture/`](docs/architecture/) | Diagramas de la arquitectura |
| [`docs/evidencias/`](docs/evidencias/) | Salidas capturadas de las verificaciones |
| `services/*/README.md` | Dominio, esquema y endpoints de cada servicio |

Las guías de instalación en otro equipo, de integración y de sustentación están
también en [`docs/`](docs/).
