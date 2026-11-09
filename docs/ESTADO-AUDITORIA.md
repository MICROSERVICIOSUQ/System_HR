# Estado de la auditoría tras Dev 1, 2, 3 y 4

Revisión del repositorio contra el **punto 3** del informe
(`informe_auditoria_sistema_rrhh_v2`, v5.0), con los cuatro lotes integrados en
`main`.

- **Fecha**: 2026-11-09
- **Commit**: `dc10232` (merge de `sebastian`, Dev 4), sobre `cfda225` (Dev 2),
  `77e19bc` (Dev 1) y `ce7a34e` (Dev 3)
- **Método**: inspección estática del código integrado + ejecución de las suites
  que corren en este equipo. El sistema no estaba levantado, así que lo marcado
  como *por verificar en vivo* necesita `docker compose up`.

**Resumen**: de los 11 puntos transversales del 3.1, **9 están cumplidos, 1
cumple en lo funcional y 1 sigue pendiente**. De los 8 roles del 3.2, **7 están
completos y 1 tiene un faltante** (el Outbox de Empleados). Pero lo más
importante de esta revisión no está en el informe: la integración de los cuatro
lotes dejó **cinco defectos nuevos**, cuatro de ellos ya corregidos y verificados
en vivo, y uno —el del token de activación— que necesita una decisión de equipo
porque rompe la mayoría de la suite BDD.

> **Actualización del mismo día.** Tras la primera revisión se corrigieron los
> defectos 2, 3, 4 y 5 de la lista de abajo, los puntos 4 y 6 del 3.1 en
> `profiles-service` y el punto 11 del 3.1. Cada arreglo se verificó con el
> sistema levantado; los comandos y sus respuestas están en
> [§5](#5-verificación-de-los-arreglos).

---

# 1. Lo que hay que arreglar antes de sustentar

Ordenado por lo que más duele, no por el orden del informe.

| # | Qué | Dónde | Estado |
|---|---|---|---|
| 0 | **El módulo `api-gateway` no compilaba**: `docker compose up --build` fallaba | `api-gateway` | ✅ corregido |
| 1 | La sanitización de tokens deja sin token de activación a la suite BDD y a 4 colecciones de Postman | `notifications-service` + `e2e-tests` + `postman` | ⛔ **necesita una decisión de equipo** |
| 2 | `/empleados/{id}/completo` llamaba a rutas que no existen: devolvía `perfil: null` y `vacaciones: null` siempre | `api-gateway` | ✅ corregido y verificado |
| 3 | `/empleados/{id}/completo` no pasaba por la validación del JWT | `api-gateway` | ✅ corregido y verificado |
| 4 | **Todo el historial de notificaciones respondía 404** por el `StripPrefix` de su ruta | `api-gateway` | ✅ corregido y verificado |
| 5 | `profiles-service` no exponía `/docs` ni OpenAPI, no medía cobertura y su etapa de Jenkins fallaba | `profiles-service` | ✅ corregido y verificado |
| 6 | Falta el campo `context` en los logs de los **3 servicios Java** (en Perfiles ya está) | `auth`, `employees`, `api-gateway` | ⚠️ pendiente |
| 7 | Outbox en Empleados | `employees-service` | ⚠️ pendiente |

Los puntos 5, 6 y 7 son del informe. Los puntos 0 a 4 son consecuencia de
integrar los lotes y **no están en el informe**, así que nadie los tenía
asignados.

## El módulo del gateway no compilaba

Antes de poder verificar nada había que arreglar esto, y conviene que el equipo
lo sepa porque bloquea a cualquiera que haga `git pull`:

```
EmpleadoCompletoController.java:[51,41] incompatible types: bad return type in
lambda expression
  ResponseEntity<capture#1 of ?> cannot be converted to
  ResponseEntity<EmpleadoCompletoDTO>
```

`thenApply` infería `ResponseEntity<EmpleadoCompletoDTO>` y el cuerpo de error de
`exceptionally` —un `Map`— ya no encajaba. Se corrige fijando el tipo del éxito a
`ResponseEntity<?>`. Como el `Dockerfile` del gateway compila con Maven dentro de
la imagen, **`docker compose up --build` fallaba para todo el sistema**, no solo
las pruebas.

---

# 2. Los defectos de integración

## 2.1 Ya no hay forma de obtener el token de activación (sin resolver)

Dev 3 hizo lo que el informe pedía: el Go de notificaciones guarda el payload
con una lista blanca de campos públicos y el token nunca se persiste.

```go
// services/notifications-service/events.go
func safePayload(event Event) map[string]any {
    data := map[string]any{}
    for _, key := range []string{"id", "empleadoId", "nombre", "apellido", "email",
        "departamentoId", "cargo", "fechaIngreso", "fechaRetiro", "fechaInicio",
        "fechaFin", "estado", "fechaCreacion", "motivo", "permanente"} {
```

`token` no está en la lista, y el filtro se aplica **también al leer**, así que
los registros antiguos tampoco lo devuelven. Correcto como medida de seguridad.

El problema es que `GET /notificaciones` era **el único canal** por el que las
pruebas obtenían el token para activar una cuenta, y nadie tocó `e2e-tests`
(último commit que lo modificó: `0744723`, Reto 7, anterior a los cuatro lotes):

```java
// e2e-tests/.../support/ApiCliente.java:249
public String tokenDeEvento(String tipoEvento, String email) {
    ...
    Object token = ((Map<String, Object>) datos).get("token");   // ahora siempre null
```

Ese método lo usa `eventualmenteSeCreoElUsuario()`, que hace
`assertThat(token).isNotBlank()` y es la precondición
*"que existe un empleado activo con credenciales establecidas"*. Es decir:

- **02-onboarding** (9 escenarios), **03-vacaciones** (7) y **04-offboarding**
  (4) usan esa precondición en sus `Antecedentes` → **20 escenarios**.
- **01-seguridad** la usa en la regla del rol USER y en el escenario del token de
  reset → **7 más**.

En total, **unos 27 de los 39 escenarios fallan**, no por un fallo de lógica
sino porque la precondición ya no puede cumplirse. Lo mismo ocurre en Postman:
leen `payload.data.token` las colecciones `auth-service` (petición 5.1),
`perfiles-service`, `ciclo-vida-cuenta` y `reto7-sustentacion`.

### Confirmado con el sistema levantado

Ya no es una lectura del código. La colección de Perfiles, ejecutada contra el
sistema arriba:

```console
$ newman run postman/perfiles-service.postman_collection.json -e postman/system-hr...
│                requests │                19 │                 0 │
│              assertions │                38 │                 9 │

 1.  AssertionError  El script previo no obtuvo el token de activacion
 2.  AssertionError  Status 200
 3.  AssertionError  Rol USER
 ...
 6.  AssertionError  Status 403   expected 403 but got 401
 8.  AssertionError  expected 'Token inválido o expirado' to include 'no tiene permiso'
```

Los ocho fallos restantes son consecuencia del primero: sin token de activación
no hay cuenta activa, sin cuenta activa no hay login de `USER`, y sin `USER`
ninguna de las aserciones de la regla ABAC puede ejecutarse. **Las pruebas de
403 no fallan porque la regla esté mal: fallan porque nunca llegan a probarla.**

Y la causa se ve directamente en el historial:

```console
$ curl -s "$B/notificaciones?size=3" -H "Authorization: Bearer $TOKEN"
usuario.recuperacion | laura.mendez@empresa.com | payload.data: ['email', 'id']
```

**Hay que decidir el canal legítimo**, y es una decisión de equipo, no un
arreglo local. Tres opciones:

1. **Un endpoint interno en `auth-service`** (`GET /auth/internal/activacion/{empleadoId}`,
   solo ADMIN, o restringido a la red interna) que emita o devuelva el token
   vigente. Es el que mejor se defiende: el token sale de quien lo firma, no del
   historial de correos, y las pruebas dejan de depender de Notificaciones.
2. **Que Notificaciones conserve el token cifrado o con un TTL corto** y lo
   exponga solo a ADMIN. Reabre parte de lo que el informe pedía cerrar.
3. **Que las pruebas lo lean de la base de `auth-service`**. Funciona, pero
   acopla la suite E2E al esquema interno de un servicio, que es justo lo que una
   prueba de caja negra no debe hacer.

Recomiendo la 1.

## 2.2 El endpoint agregado pedía rutas que no existen

```java
// api-gateway/.../EmpleadoCompletoClient.java
request(profilesUrl,  "/perfiles/empleadoId/{id}", id);
request(vacationsUrl, "/vacaciones/empleadoId/{id}", id);
```

Las rutas reales son otras:

| Llamada del gateway | Lo que existe en el servicio |
|---|---|
| `GET /perfiles/empleadoId/{id}` | `GET /perfiles/{empleadoId}` (`profile.routes.ts:17`) |
| `GET /vacaciones/empleadoId/{id}` | `GET /vacaciones/balances/{employeeId}` (`balance.routes.ts:13`) |

Express no hace coincidir `/perfiles/empleadoId/E601` con `/:empleadoId`, que es
de un solo segmento: responde **404**. Y el 404 llega al circuit breaker, que
ejecuta su fallback:

```java
private CompletableFuture<JsonNode> profilesFallback(...) {
    LOG.warn("Fallback perfil=null para empleado id={} ...");
    return CompletableFuture.completedFuture(null);
}
```

O sea: el endpoint **respondía 200 con `perfil: null` y `vacaciones: null`
siempre**, y el log decía "fallback" como si los servicios estuvieran caídos. La
resiliencia está bien construida —circuit breaker por dependencia, timeout, el
empleado como obligatorio y los otros dos como opcionales— y precisamente por
eso el error no se notaba: el fallback tapaba el 404. No había ninguna prueba del
agregador que lo hubiera detectado.

### Cómo quedó

Las dos rutas corregidas, y dos cosas más que el arreglo hacía necesarias:

- **El 404 de una dependencia opcional ya no llega al circuit breaker.** Un
  empleado puede no tener perfil o saldo todavía, porque el read model se
  construye por eventos y puede ir por detrás: eso es una respuesta correcta del
  servicio, no un fallo. Si contara como fallo, diez consultas a empleados
  recién creados abrirían el circuito. Se resuelve con `onErrorResume` sobre
  `NotFound` antes de que el breaker lo vea.
- **El 404 del empleado tampoco cuenta como fallo**, por la misma razón —es la
  distinción que `employees-service` ya hacía con el 404 de departamentos— pero
  sí se propaga al cliente como 404. Se configura con `ignoreExceptions` en el
  `resilience4j` del gateway.
- **Siete pruebas nuevas** (`EmpleadoCompletoClientTest`) fijan la ruta exacta
  que se pide a cada servicio y la diferencia entre 404 y 5xx. No necesitan red:
  sustituyen la `ExchangeFunction` del WebClient, así que corren también en
  equipos donde `GatewayTest` no puede abrir un socket.

## 2.3 El endpoint agregado no validaba el JWT

`JwtAuthenticationFilter` y `AuthorizationFilter` son `GlobalFilter`:

```java
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {
public class AuthorizationFilter implements GlobalFilter, Ordered {
```

Un `GlobalFilter` solo corre dentro de la cadena de **rutas** de Spring Cloud
Gateway. `/empleados/{id}/completo` no es una ruta: es un `@RestController` local
del gateway, y en WebFlux el `RequestMappingHandlerMapping` (orden 0) gana al
`RoutePredicateHandlerMapping` (orden 1). La petición la atiende el controlador y
**los dos filtros se saltan**.

Si se confirma, el endpoint que devuelve el expediente completo de un empleado
—datos laborales, perfil personal y vacaciones— responde sin token y sin aplicar
la regla de propiedad del ADR-008, mientras que `GET /empleados/{id}` sí la
exige. Es un agujero en el único componente expuesto al host.

**Confirmado con el sistema arriba**: respondía 200 sin `Authorization`.

### Cómo quedó

Los dos filtros pasan de `GlobalFilter` a **`WebFilter`**. Un `WebFilter` envuelve
a todos los manejadores —rutas y controladores locales—, así que la puerta queda
cerrada también para cualquier controlador que se añada después, que es lo que
las otras dos salidas posibles (una comprobación dentro del controlador, o mover
la agregación detrás del gateway) no garantizan.

El cambio fue pequeño porque ninguno de los dos filtros usaba nada propio del
gateway: solo `ServerWebExchange` y la cadena. Las 14 pruebas ABAC siguieron
pasando sin tocarlas, porque `WebFilterChain` tiene la misma forma funcional que
`GatewayFilterChain` y el doble era una lambda.

Se añadieron **dos pruebas ABAC** para la ruta agregada y una clase nueva,
`SeguridadDeControladoresLocalesTest`, que arranca el contexto con
`WebEnvironment.MOCK` y comprueba el 401 real del filtro. Esa clase **se salta en
este equipo**: aunque no levanta servidor, el contexto crea el
`ClientHttpConnector` de reactor-netty y eso necesita un `Selector`, que en
Windows se construye con un par de sockets en loopback. La condición es explícita
(`@EnabledIf`), de modo que se omite aquí y se ejecuta en el pipeline, que corre
en Linux.

## 2.4 El historial de notificaciones respondía 404 (encontrado al verificar)

Esto no estaba en la primera revisión: apareció al probar el sistema levantado.
`GET /notificaciones` y todas sus variantes respondían **404**, aunque el
servicio en Go estaba sano, consumiendo eventos y simulando los correos —sus
logs lo demostraban—.

La causa estaba en la ruta del gateway:

```yaml
- id: notificaciones-route
  predicates:
    - Path=/notificaciones/**
  filters:
    - StripPrefix=1      # ← quitaba /notificaciones antes de reenviar
```

El servicio en Go registra sus rutas **con** el prefijo puesto
(`mux.HandleFunc("GET /notificaciones", ...)`) y además sirve el alias
`/notifications`. Con `StripPrefix=1`, `GET /notificaciones` llegaba como
`GET /` y caía en el manejador comodín. La versión anterior en Node montaba sus
rutas en la raíz, y por eso el filtro tenía sentido entonces: al reconstruir el
servicio nadie revisó la ruta.

Es, además, justo el entregable del Dev 4 que el informe enuncia como *"Corregir
el enrutamiento de notificaciones hacia `/notificaciones`"*.

**Cómo quedó**: sin `StripPrefix`, y con el predicado ampliado para que cubra
también las rutas sin segmento adicional y el alias en inglés. Verificado: las
cuatro rutas responden 200 y devuelven filas reales.

---

# 3. Matriz 3.1 — Características transversales

| # | Característica | Informe | Hoy | Qué falta |
|---|---|---|---|---|
| 1 | Database-per-Service | CUMPLE | ✅ | — |
| 2 | Diversidad tecnológica (4 lenguajes) | PENDIENTE | ✅ | Java 17/21, TypeScript, Python 3.12, Go 1.25 |
| 3 | Aislamiento de puertos | CUMPLE | ✅ | Solo el gateway publica `8080`; los 6 servicios con `expose` |
| 4 | Documentación OpenAPI y Swagger UI en `/docs` | PENDIENTE | ✅ | Perfiles ya expone `/docs` y `/openapi.json`; el gateway publica los **6** contratos |
| 5 | `/health` con `checks` | PENDIENTE | ✅ | Los 6 servicios reportan `database` y `messageBroker`; el healthcheck de Auth ya apunta a `/health` |
| 6 | Logs JSON estructurados | PENDIENTE | ⚠️ | Perfiles ya emite `context` y no usa `console.*`; **falta `context` en los 3 servicios Java** |
| 7 | Monitoreo (Prometheus) | PENDIENTE | ⚠️ | Funciona (8 targets), pero los Java siguen solo en `/actuator/prometheus`: el alias `/metrics` no se unificó |
| 8 | Trazabilidad (OpenTelemetry) | CUMPLE | ✅ | W3C Trace Context en HTTP y AMQP, 3 lenguajes en una traza |
| 9 | Gestión de secretos | PENDIENTE | ✅ | `scripts/setup-secrets.sh` ejecutable; `JWT_SECRET` vacío en `.env.example` |
| 10 | CI/CD: Jenkins y SonarQube en Compose | PENDIENTE | ✅ | `jenkins`, `sonarqube`, `sonar-db`, `sonar-setup`; 7 `Jenkinsfile` y 7 `sonar-project.properties` |
| 11 | Cobertura ≥ 70 % | PENDIENTE | ✅ | Perfiles: 49 pruebas y **95,40 %** con umbral que corta; los cinco servicios con lógica tienen umbral propio |

## Detalle de los tres puntos que cambiaron

### 4 — OpenAPI

| Servicio | Contrato | Swagger UI | Publicado en el gateway |
|---|---|---|---|
| departments | generado (Pydantic) | `/docs` | `/v3/api-docs/departamentos` |
| vacations | a mano en `app.ts` | `/docs` | `/v3/api-docs/vacaciones` ✨ |
| notifications | `openapi.json` | `/docs` | `/v3/api-docs/notificaciones` ✨ |
| employees | springdoc | `/swagger-ui.html` | `/v3/api-docs/empleados` |
| auth | springdoc | `/swagger-ui.html` | `/v3/api-docs/auth` |
| api-gateway | springdoc, agrega 6 | `/swagger-ui.html` | — |
| **profiles** | **a mano, con prueba de consistencia** ✨ | **`/docs`** ✨ | `/v3/api-docs/perfiles` ✨ |

Había dos cosas distintas. La que el informe nombra: Perfiles no tenía contrato,
y era el único de los siete. La que no nombra: el desplegable del gateway listaba
3 de 7 servicios, así que desde fuera solo se veían esos tres contratos aunque
Vacaciones y Notificaciones los sirvieran por dentro.

**Cómo quedó.** El contrato de Perfiles está en `src/docs/openapi.ts` y se sirve
en `/docs` y `/openapi.json`. Escrito a mano, porque la validación de ese
servicio también lo está y no hay modelos de los que generarlo; pero el defecto
que la auditoría encontró en el antiguo departments-service no era escribirlo a
mano, era que **nadie comprobaba que siguiera describiendo la realidad**. Aquí sí
se comprueba:

- el enum de `sortBy` y los campos del esquema `Perfil` se importan del código;
- `openapi.test.ts` verifica que las rutas documentadas son exactamente las que
  registra el router y que las propiedades de `Perfil` son exactamente las que
  devuelve `presentarPerfil`.

Si alguien añade una ruta o un campo y no toca el contrato, la suite falla. Y el
gateway publica ahora los seis contratos, con las seis entradas en el
desplegable.

### 6 — El campo `context`

```json
{"timestamp":"2026-11-08T22:59:11.687Z","level":"WARN","service":"auth-service",
 "traceId":"46072e07...","spanId":"44cf2297...","logger":"c.m.auth.service.AuthService",
 "thread":"main","message":"[AUTH] Recuperación rechazada por estado 'SUSPENDIDA_TEMPORAL'"}
```

Está todo menos `context`, y eso sigue igual en los tres servicios Java. Quien sí
lo emite es Dev 3, en los dos servicios que tocó:

```go
// notifications-service/observability.go — lo garantiza incluso si nadie lo pasa
r.AddAttrs(slog.Any("context", map[string]any{}))
```

```ts
// vacations-service/src/observability/logger.ts
message, context: { details: safeDetails },
```

**Perfiles ya lo emite.** Su logger agrupa los datos del registro bajo `context`
en lugar de dejarlos en la raíz, y se sustituyeron los **27 `console.*`** que
quedaban por el logger estructurado, incluidos los del consumidor de RabbitMQ y
los del arranque. Los dos registros del *bootstrap* de OpenTelemetry siguen
escribiéndose a mano —ese módulo se carga antes que cualquier otro y no debe
importar nada de la aplicación— pero con la misma forma de documento. Verificado
en vivo:

```json
{"timestamp":"2026-11-09T18:11:46.185Z","level":"INFO","service":"profiles-service",
 "message":"[PROFILE] [CREADO] Perfil creado","traceId":"26b4b7d1a6d0b9af074f45763b73661c",
 "spanId":"2d0dca16a52b967c",
 "context":{"empleadoId":"ELOG2","eventId":"a122d0ab-...","nombre":"Ana Ruiz",
            "email":"ana.log2@empresa.com"}}
```

De paso se descartan los `trace_id`, `span_id` y `trace_flags` que inyecta la
instrumentación automática de OpenTelemetry: son los mismos valores que
`traceId` y `spanId` en snake_case, y dejarlos obligaría a una consulta de Loki a
saber cuál de los dos nombres mirar.

**Lo que queda**: los tres `logback-spring.xml` de Java. La forma más limpia es
el proveedor `<arguments>` de logstash-logback-encoder agrupando los argumentos
estructurados bajo `context`, de modo que un
`log.info("...", kv("empleadoId", id))` salga ya dentro del objeto. No es un
cambio de una línea a ciegas: conviene verificarlo levantando un servicio y
mirando su salida, como se hizo con Perfiles.

### 11 — Cobertura

| Servicio | Umbral | Dónde se aplica |
|---|---|---|
| employees | 70 % LINE | JaCoCo `check` en el `pom.xml`, rompe `verify` |
| departments | 70 % | `--cov-fail-under=70` en `pytest.ini` |
| vacations | 70 % líneas | `--test-coverage-lines=70` en `npm test` |
| notifications | 70 % | el `Jenkinsfile` corta si `go tool cover` baja de 70 |
| **profiles** | **70 % líneas** ✨ | **`--test-coverage-lines=70` en `npm test`** |
| auth | — | solo el Quality Gate `RRHH-70` de Sonar |
| api-gateway | — | solo el Quality Gate `RRHH-70` de Sonar |

Perfiles tenía **3 pruebas** (`profile.validation.test.ts`) y su `npm test` era
`prisma generate && tsx --test src/tests/*.test.ts`: sin
`--experimental-test-coverage` y sin reportero LCOV. Su `Jenkinsfile` hacía:

```groovy
npm test -- --coverage
test -f coverage/lcov.info
```

El `--coverage` se le pasaba a `tsx` como argumento suelto y **no se creaba
`coverage/lcov.info`**, así que esa etapa fallaba. Comprobado ejecutándolo: las 3
pruebas pasaban y no se generaba el directorio `coverage/`.

**Cómo quedó.** 49 pruebas y **95,40 % de líneas** sobre servicio, controlador,
mapeador, errores y contrato, con un umbral del 70 % que **rompe el comando**
—verificado subiéndolo a 99 y comprobando que sale con código 1—. Se añadieron
tres suites: `profile.service.test.ts` (reglas de negocio, idempotencia por
`eventId`, archivado y el consumo de los tres eventos, con dobles en memoria),
`profile.mapper.test.ts` y `openapi.test.ts`. El `Jenkinsfile` ahora llama a
`npm test` sin el `-- --coverage`, y el informe LCOV se escribe donde Sonar lo
espera.

Un detalle que bloqueaba el pipeline aparte del anterior: `prisma generate`
exigía `DATABASE_URL` aunque no se conecte a nada, así que `npm test` fallaba en
cualquier equipo sin esa variable y también en el contenedor de Node del
pipeline. `prisma.config.ts` lleva ahora un valor de reserva que apunta al puerto
1; no debilita el arranque del servicio, porque el cliente en ejecución usa
`src/config/db.ts`, que sigue fallando si falta la variable.

Que el Quality Gate exista está bien resuelto —`ci/configurar-sonar.py` crea
`RRHH-70` con `coverage < 70` y `new_coverage < 70`— pero un proyecto que no sube
informe no tiene métrica de cobertura, y entonces el gate no protege nada en ese
proyecto. Eso era exactamente el caso de Perfiles.

---

# 4. Matriz 3.2 — Por rol y microservicio

| Rol | Faltantes del informe | Hoy |
|---|---|---|
| **Gestión de Empleados** | 1. Unificar auditoría en `GET /empleados` · 2. `fechaRetiro` a `LocalDateTime` · 3. **Outbox en RabbitMQ** | 1 ✅ · 2 ✅ · 3 ❌ |
| **Gestión de Departamentos** | 1. Reconstruir en Python · 2. Swagger UI en `/docs` · 3. Validar borrado con empleados | ✅ ✅ ✅ |
| **Autenticación** | 1. `recoverPassword` solo ACTIVA · 2. Healthcheck a `/health` | ✅ ✅ |
| **Gestión de Perfiles** | 1. Cinco campos · 2. Renombrar `empleadoId` · 3. Test ABAC 403 · 4. `archivado: true` | ✅ ✅ ✅ ✅ · y ya con `/docs`, cobertura y `context` |
| **Gestión de Vacaciones** | 1. `fechaFin > fechaInicio` · 2. Scheduler `lte: hoy` · 3. Empleado ACTIVO · 4. `COMPLETADA` → `FINALIZADA` | ✅ ✅ ✅ ✅ |
| **Notificaciones** | 1. Reconstruir en Go · 2. Los 6 eventos · 3. `/notificaciones` y por empleado · 4. Sanitizar tokens | ✅ ✅ ✅ ✅ (ver 2.1) |
| **API Gateway** | 1. Ruta `/notificaciones` · 2. Endpoint agregado con fallback | ✅ ✅ tras corregir 2.2, 2.3 y 2.4 |
| **DevOps / CI-CD** | `setup-secrets.sh`, Jenkins/Sonar en Compose, redes segmentadas | ✅ ✅ ✅ |

## Lo que sí quedó bien de Dev 3 y Dev 4

Vale la pena reconocerlo porque son los lotes más grandes:

- **Vacaciones** cumple las cuatro reglas, con el orden correcto de
  validaciones: rango, fecha no pasada, empleado existente, empleado `ACTIVO`,
  solapamiento. El enum es `FINALIZADA` en el código, en el OpenAPI y en las
  pruebas.
- **Notificaciones en Go** escucha los 6 eventos obligatorios **y dos más**
  (`vacaciones.iniciadas`, `vacaciones.finalizadas`), sirve `/notificaciones`,
  `/notificaciones/empleado/{empleadoId}`, `/health`, `/metrics`, `/docs` y
  `/openapi.json`, devuelve 405 en los verbos de escritura, y emite `traceId` y
  `context` en cada log.
- **Las redes están segmentadas de verdad**: `data-broker-net` lleva
  `internal: true`, así que las bases y el broker no tienen salida a internet, y
  cada servicio está solo en las redes que necesita.
- **El agregador tiene la resiliencia bien pensada**: circuit breaker por
  dependencia, timeout, Empleados obligatorio (falla la petición) y Perfiles y
  Vacaciones opcionales (degradan a `null`). Solo hay que corregirle las rutas.

## El Outbox

No está implementado en ninguna parte. Lo que hay es la decisión documentada de
**no** hacerlo, en `services/employees-service/README.md`:

> ### ¿Por qué no Outbox Pattern en esta etapa?

El informe lo pide en el 3.2 de Empleados y en los vacíos del Dev 1, pero **no lo
incluyó en los entregables del Dev 1 en el punto 5**, que es la lista por la que
se repartió el trabajo. De ahí que nadie lo haya hecho.

Las publicaciones siguen siendo *fire-and-forget* en Empleados, Auth y
Vacaciones: si RabbitMQ está caído en el instante entre el `COMMIT` y el
`convertAndSend`, el evento se pierde y no hay reintento. Hoy eso se ve en el
log, y nada más.

Implementarlo es una tabla `outbox_events` escrita en la misma transacción que el
cambio de negocio, más un publicador periódico que marca lo enviado. Son 2-3
horas en un servicio, pero **se defiende mejor explicando por qué se dejó fuera
que a medio hacer**: si solo se implementa en Empleados, el sistema sigue
perdiendo eventos por Auth y Vacaciones.

---

# 5. Verificación de los arreglos

Todo lo que sigue se ejecutó el 2026-11-09 con el sistema levantado. El puerto
se consulta como `http://[::1]:8080` por el conflicto con Apache descrito más
abajo.

### El endpoint agregado exige el token (2.3)

```console
$ curl -s -w "
HTTP %{http_code}
" "http://[::1]:8080/empleados/E601/completo"
{"timestamp":"2026-11-09T17:51:20.551Z","status":401,"error":"Unauthorized",
 "message":"Se requiere un token de acceso en la cabecera Authorization: Bearer <token>",
 "path":"/empleados/E601/completo"}
HTTP 401
```

Con las cabeceras de identidad falsificadas (`X-Auth-User`, `X-Auth-Role`)
también responde 401: el filtro las borra antes de validar. Y `/health` sigue
devolviendo 200 sin token, que es lo que necesita el healthcheck de Docker.

### El agregado devuelve los tres bloques (2.2)

```console
$ curl -s "$B/empleados/EAGG1/completo" -H "Authorization: Bearer $TOKEN"
empleado   -> OBJETO con 12 campos
perfil     -> OBJETO con 19 campos
vacaciones -> OBJETO con 11 campos
```

Y la resiliencia que pedía el informe, con Perfiles detenido:

```console
$ docker compose stop profiles-service
$ curl -s "$B/empleados/EAGG1/completo" -H "Authorization: Bearer $TOKEN"
empleado: presente | perfil: NULO (degradado) | vacaciones: presente
HTTP 200
```

Un empleado inexistente responde **404**, no 503.

### El historial de notificaciones (2.4)

```console
/notificaciones                    -> 200
/notificaciones?size=3             -> 200
/notificaciones/empleado/EAGG1     -> 200
/notifications                     -> 200
```

Esa misma consulta confirma el defecto 1, que sigue abierto: el `payload.data`
de un `usuario.recuperacion` trae `['email', 'id']` y **ningún token**.

### Los seis contratos por el gateway (3.1-4)

```console
auth             200      perfiles         200
empleados        200      vacaciones       200
departamentos    200      notificaciones   200
```

`GET /v3/api-docs/perfiles` devuelve `API de Perfiles 2.0.0`, 4 rutas, 19 campos
en el esquema `Perfil` y el esquema `bearerAuth`. `GET /perfiles/docs` sirve la
Swagger UI.

### Las suites

| Suite | Resultado |
|---|---|
| `api-gateway` · `EmpleadoCompletoClientTest` | 7 / 7 ✅ |
| `api-gateway` · `AuthorizationFilterTest` | 16 / 16 ✅ (14 previas + 2 nuevas) |
| `api-gateway` · `SeguridadDeControladoresLocalesTest` | 4 omitidas en este equipo, se ejecutan en el pipeline |
| `api-gateway` · `GatewayTest` | 7 errores, **preexistentes** (ver más abajo) |
| `profiles-service` · `npm test` | 49 / 49 ✅, 95,40 % con umbral del 70 % |
| `profiles-service` · `npx tsc --noEmit` | sin errores ✅ |

### `GatewayTest` merece una nota aparte

Sus 7 pruebas son del **Reto 3** y nunca se actualizaron cuando el Reto 5 añadió
el JWT: proxifican `/empleados` sin token y esperan 202. En este equipo no se
pueden ejecutar (Netty no puede crear su *event loop*), pero en el pipeline, que
corre en Linux, fallarían con 401 —y fallaban ya antes de estos cambios, porque
un `GlobalFilter` sí se aplicaba a las rutas—. Es independiente de lo corregido
aquí y conviene arreglarlo: basta que el test firme un token con el secreto de
desarrollo, o que declare esas rutas públicas en su propia configuración.

---

# 6. Cómo comprobar cada cosa

Los dos primeros no necesitan Docker:

```bash
cd services/profiles-service && npm test -- --coverage && ls coverage
```

```bash
grep -rn "payload.data.token" e2e-tests/src postman/*.json
```

El resto, con el sistema arriba:

```bash
docker compose up -d --build
```

```bash
curl -i http://localhost:8080/empleados/E601/completo
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/empleados/E601/completo | python -m json.tool
```

```bash
curl -s "http://localhost:8080/notificaciones?size=1" -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

```bash
cd e2e-tests && BASE_URL="http://[::1]:8080" ./mvnw test
```

Dos avisos prácticos para la sustentación:

- **Go no está instalado en este equipo**, así que `go test ./...` de
  Notificaciones solo corre dentro de Docker o en el pipeline. La imagen se
  construye con `golang:1.25-alpine` y sí compila.
- Sigue el conflicto del **puerto 8080**: Apache lo tiene en `127.0.0.1` y Docker
  publica en `[::1]`. La suite BDD necesita `BASE_URL="http://[::1]:8080"`.

## Dos avisos sobre el arranque, por el lote del Dev 4

**1. `docker compose up` falla con un `.env` anterior al merge.** El Compose
exige ahora tres variables nuevas con `${VAR:?...}`:

```
error while interpolating services.jenkins.environment.JENKINS_ADMIN_PASSWORD:
required variable JENKINS_ADMIN_PASSWORD is missing a value:
Ejecute scripts/setup-secrets.sh
```

Son `JENKINS_ADMIN_PASSWORD`, `SONAR_DB_PASSWORD` y `SONAR_ADMIN_PASSWORD`. El
*fail-fast* es correcto —es la decisión que el propio informe aprueba— pero
afecta a cualquiera que ya tuviera su `.env`, aunque solo quiera levantar los
microservicios y no Jenkins.

**2. Cuidado con `scripts/setup-secrets.sh` sobre un entorno que ya funciona.**
El informe pedía un script que generara *la clave JWT*. El script hace más:

```awk
} else if ($0 ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*DB_PASSWORD=/) {
    print replace_value($0, "DB_PASSWORD", db_password)
} else if ($0 ~ /^[[:space:]]*RABBITMQ_PASSWORD=/) {
    print replace_value($0, "RABBITMQ_PASSWORD", rabbitmq_password)
```

Reescribe **todas** las contraseñas de base de datos y la de RabbitMQ con valores
nuevos. PostgreSQL y RabbitMQ solo aplican esas credenciales al inicializar su
volumen, así que en un entorno con volúmenes ya creados —el de cualquiera que
haya levantado el sistema antes— los servicios dejarían de poder conectarse, y
recuperarlo exige borrar los volúmenes y perder los datos.

Mientras eso no se acote, para arrancar basta pasar las tres variables en la
misma línea, sin tocar el `.env`:

```bash
JENKINS_ADMIN_PASSWORD=x SONAR_DB_PASSWORD=x SONAR_ADMIN_PASSWORD=x docker compose up -d
```

Es lo que se usó para esta verificación. Lo razonable es que el script solo
rellene las claves **vacías** y nunca sustituya una que ya tiene valor.

---

# 7. Resultado de las suites en este equipo

Ejecutadas el 2026-11-09 sobre el código integrado.

| Suite | Resultado |
|---|---|
| `auth-service` | 24 / 24 ✅ |
| `api-gateway` (ABAC + agregador) | 16 / 16 y 7 / 7 ✅ |
| `departments-service` | 50 / 50, 80,18 % ✅ |
| `profiles-service` | **49 / 49, 95,40 % con umbral del 70 %** |
| `employees-service` | 60 verdes, 7 omitidas, 14 no ejecutables aquí |
| `notifications-service` | no ejecutable aquí (falta Go) |
| BDD | **no ejecutada**: la precondición del token está rota (ver 2.1) |

Las 21 pruebas no ejecutables son las que abren un socket en loopback
(`DepartmentCacheFallbackTest`, `DepartmentCircuitBreakerTest`, `GatewayTest`):
fallan con `Unable to establish loopback connection`, que es una restricción de
este equipo y no del código.
