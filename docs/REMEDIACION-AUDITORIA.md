# Remediación de la auditoría — Dev 1 y Dev 2

La auditoría estática del sistema (`informe_auditoria_sistema_rrhh_v2`) dejó una
lista de hallazgos y una metodología para repartirlos entre cuatro
desarrolladores, cada uno responsable de dos servicios y de un lenguaje.

Este documento registra lo que se implementó en los dos primeros lotes, por qué
cada cambio se hizo así y cómo comprobarlo.

| Dev | Servicios | Lenguajes | Hallazgos |
|---|---|---|---|
| **Dev 1** | employees-service, departments-service | Java, Python | Ruta unificada de auditoría · `fechaRetiro` con hora · Reconstruir departments en FastAPI con Swagger nativo |
| **Dev 2** | auth-service, profiles-service | Java, TypeScript | `recoverPassword` solo para cuentas ACTIVA · healthcheck a `/health` · 5 campos canónicos · `empleadoId` · marca de archivado · prueba ABAC 403 |

La restricción que gobernó todo el trabajo: **el sistema tenía que seguir
funcionando**. No es una restricción retórica —es la que explica la mitad de las
decisiones que siguen—: hay cinco colecciones de Postman, 39 escenarios BDD y
seis servicios que se consumen entre sí, y ninguno de ellos debía notar los
cambios salvo donde el cambio era precisamente el objetivo.

---

# Dev 1

## 1.1 La auditoría de retiros queda en una sola ruta

### El problema

Había **dos implementaciones de la misma consulta**. `GET /empleados` filtraba
empleados con un `Specification` dinámico, y `GET /empleados/auditoria` repetía la
consulta por su cuenta para filtrar retirados por rango de fecha. Dos caminos
para el mismo dato significan dos sitios donde corregir un error y dos
oportunidades de que se comporten distinto: la paginación, el ordenamiento y el
formato de la respuesta estaban implementados una vez en cada uno.

### La decisión

`GET /empleados` es la **ruta canónica**. Si recibe `desde` y `hasta`, la
consulta se convierte en la auditoría de retiros:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/empleados?desde=2026-01-01&hasta=2026-12-31"
```

Semántica, explícita porque es fácil equivocarse:

- **El estado se fuerza a `RETIRED`.** No es un filtro más que se combine con
  `estado`: pedir un rango de retiro de empleados activos no tiene sentido, y
  dejarlo abierto obligaría a decidir qué hacer con `?estado=ACTIVO&desde=...`.
- **Los dos límites son inclusivos.** `desde` se toma desde las 00:00:00 y
  `hasta` se expande al final del día, así que un retiro de la fecha `hasta` a
  cualquier hora queda dentro.
- **Los dos parámetros son obligatorios si se envía uno.** Un rango abierto
  —"todos los retiros desde enero"— es una consulta sin techo, y acotar es
  precisamente lo que una auditoría necesita.

`GET /empleados/auditoria` **sigue existiendo** y delega en la ruta canónica.
Está marcada `@Deprecated` en el código y documentada como alias en el OpenAPI.
No se eliminó porque está publicada: las colecciones de Postman la usan y
cualquier cliente que la consulte seguiría esperando su respuesta.

### Los códigos de error son del alias, no solo de la ruta nueva

```
400  falta 'desde'          El parámetro de fecha inicial 'desde' es obligatorio
400  falta 'hasta'          El parámetro de fecha final 'hasta' es obligatorio
400  desde posterior        La fecha 'desde' (...) no puede ser posterior a la fecha 'hasta' (...)
```

Esto fue una **regresión introducida y corregida durante el cambio**, y vale la
pena dejarla escrita. Al delegar el alias en `findAll`, la validación de rango
pasó a estar dentro del `if (desde != null || hasta != null)`: con los dos
parámetros ausentes, la condición era falsa, la validación no corría y
`/empleados/auditoria` sin parámetros devolvía **200 con la lista completa de
empleados** en lugar del 400 de antes. Una auditoría que responde "todos" cuando
no se le dice qué rango auditar es peor que una que falla.

La corrección: el alias valida el rango explícitamente antes de delegar. Su
contrato no depende de que la ruta canónica lo haga por él.

### Cómo comprobarlo

Colección `empleados-service`, peticiones **20**, **21** y **22**: la ruta
unificada, la equivalencia del alias con la canónica, y una auditoría del día de
hoy que incluye un retiro ocurrido esta misma mañana —que es la prueba que ataba
este punto con el siguiente—.

---

## 1.2 `fechaRetiro` pasa a `LocalDateTime`

### El problema

`fechaRetiro` era un `LocalDate`: solo el día. El retiro de un empleado es un
hecho con hora —ocurre cuando alguien ejecuta el `DELETE`— y guardar solo el día
pierde información que la auditoría necesita. Dos retiros del mismo día no se
pueden ordenar entre sí, y una auditoría "de hoy" no puede distinguir lo que pasó
antes o después de un cambio de turno.

El síntoma visible estaba en el read model: el perfil del empleado recibía la
fecha de retiro **a medianoche**, porque era lo único que el evento transportaba.

### Los cambios

| Capa | Cambio |
|---|---|
| Modelo | `private LocalDateTime fechaRetiro;` |
| Esquema | `V3__fecha_retiro_a_timestamp.sql` → `TIMESTAMP(3)` con `USING fecha_retiro::timestamp` |
| Repositorio | `findByEstadoAndFechaRetiroBetween(estado, LocalDateTime, LocalDateTime)` |
| Consulta | `cb.between(root.get("fechaRetiro"), desde.atStartOfDay(), hasta.atTime(LocalTime.MAX))` |
| Evento | `EmployeeEventData` publica `employee.getFechaRetiro()` |

La migración es **`ALTER COLUMN ... TYPE`, no una columna nueva**. PostgreSQL
convierte cada `date` existente a `timestamp` a medianoche: los retiros
históricos quedan con hora 00:00:00, que es exactamente la precisión que se tenía
de ellos. Inventarles una hora sería peor que admitir que no se conoce.

### El defecto que apareció al mirarlo de cerca

`EmployeeEventData` no publicaba la fecha de retiro almacenada: publicaba
`LocalDate.now()`. El valor coincidía casi siempre —el evento se emite justo
después del retiro— pero no era el mismo dato: un reproceso del evento, o un
retiro registrado con fecha distinta a la de hoy, habría publicado la fecha
equivocada. Es un error que existía desde antes y que solo se vio porque el
cambio de tipo obligó a leer esa línea.

### La trampa operativa

El cambio pareció no funcionar: el perfil seguía recibiendo medianoche. La causa
no estaba en el código sino en que **la imagen de `employees-service` no se había
reconstruido**, así que la migración V3 nunca corrió y la columna seguía siendo
`date`. Un `docker compose up -d` no reconstruye por sí solo:

```bash
docker compose up -d --build employees-service
```

```bash
docker compose exec database-empleados psql -U postgres -d gestion_empleados -c "\d empleado"
```

### La versión de Java

El hallazgo pedía "Java 21" para estos servicios. Se verificó antes de tocar
nada:

| Módulo | Java | Spring Boot |
|---|---|---|
| api-gateway | **17** | 3.2.5 (Spring Cloud Gateway 2023.0.1) |
| employees-service | **21** | 3.4.1 |
| auth-service | **21** | 3.4.1 |

`employees-service` y `auth-service` ya estaban en 21, así que no hubo nada que
cambiar. El gateway está en 17 y **se dejó en 17**: subirlo no era parte del
hallazgo, y cambiar la versión del lenguaje del único componente expuesto al host
por una línea de documentación es riesgo sin beneficio.

---

## 1.3 `departments-service` reconstruido en Python (FastAPI)

El cambio más grande del lote: el servicio se reescribió de Node/Express/Prisma a
**Python 3.12 con FastAPI, SQLAlchemy y Alembic**, conservando el contrato HTTP
byte a byte y sin migrar la base de datos.

- El razonamiento, lo que no cambió y las alternativas descartadas están en
  **[ADR-012](decisions/ADR-012-departments-en-python.md)**.
- La estructura, los endpoints, las variables y los comandos están en el
  **[README del servicio](../services/departments-service/README.md)**.

Lo esencial, para no obligar a abrir los dos:

| | Antes | Ahora |
|---|---|---|
| Lenguaje | Node 22 / TypeScript | Python 3.12 |
| Framework | Express 5 | FastAPI |
| Datos | Prisma | SQLAlchemy + Alembic |
| OpenAPI | `src/docs/openapi.ts` escrito a mano | generado de los modelos Pydantic, Swagger UI en `/docs` |
| Pruebas | solo validación HTTP | 50 pruebas, 80 % de cobertura, umbral del 70 % |
| `/health` | `{"status":"ok"}` sin comprobar nada | comprueba base y broker, 503 si falla |

El motivo del hallazgo no era solo el lenguaje: la auditoría encontró que el
documento OpenAPI escrito a mano y el esquema de validación se habían
**desincronizado**. Con FastAPI no hay dos fuentes, así que ese error deja de ser
posible.

Y la consecuencia que se puede enseñar: una petición de onboarding produce ahora
**48 spans en 7 servicios con tres lenguajes** en una sola cascada de Zipkin. Es
la demostración de que la propagación W3C Trace Context funciona entre lenguajes,
no una afirmación del README.

El único ajuste fuera del servicio fue el `SetPath` del gateway, porque FastAPI
sirve el contrato en `/openapi.json` y la versión anterior lo servía en
`/docs/openapi.json`.

---

# Dev 2

## 2.1 `recoverPassword` solo para cuentas ACTIVA

### El problema

`POST /auth/recover-password` es un endpoint **público** —tiene que serlo: quien
olvidó su contraseña no puede autenticarse— y emitía un token de reset para
cualquier cuenta que existiera, en cualquier estado.

Eso convertía un endpoint público en un **generador de tokens de activación a
demanda**: bastaba conocer el correo de una cuenta recién creada para obtener un
token válido y establecerle la contraseña, sin pasar por el token que el
`auth-service` envía al crear la cuenta. El camino de activación tenía una puerta
lateral.

Y el caso de la cuenta suspendida era igual de incorrecto por otra razón: un
empleado de vacaciones tiene la cuenta `SUSPENDIDA_TEMPORAL`, y dejarle recuperar
la contraseña le permitía volver a entrar durante la suspensión.

### La decisión

```java
if (cuenta.getEstado() != AccountStatus.ACTIVA) {
    log.warn("[AUTH] Recuperación rechazada por estado '{}' | empleado='{}'", ...);
    throw new CuentaNoHabilitadaException(explicarRecuperacion(cuenta.getEstado()));
}
```

Responde **401** con un mensaje distinto por estado:

| Estado | Mensaje |
|---|---|
| `PENDIENTE_ACTIVACION` | La cuenta aún no ha sido activada: establezca su contraseña con el token de activación que recibió, no con una recuperación |
| `SUSPENDIDA_TEMPORAL` | La cuenta está suspendida temporalmente porque el empleado se encuentra de vacaciones |
| `DESACTIVADA_PERMANENTE` | La cuenta está desactivada de forma permanente |

Tres decisiones que no son evidentes:

- **401 y no 403 o 409.** Es el mismo código que ya devuelve el login cuando la
  cuenta no está habilitada, y lo maneja el mismo `@ExceptionHandler`. Un código
  distinto para la misma situación obligaría a cada cliente a aprender dos
  reglas.
- **Mensaje distinto por estado, y no uno genérico.** Aquí el correo ya quedó
  confirmado por el 404 previo —esta rama solo se alcanza si la cuenta existe—
  así que no hay nada que proteger callándose el motivo, y sí hay algo útil que
  decir: al dueño de una cuenta sin activar hay que explicarle que su camino es
  el token de activación, no la recuperación. Es un método aparte
  (`explicarRecuperacion`) y no el `explicar` del login, precisamente porque el
  mensaje de `PENDIENTE_ACTIVACION` tiene que decir algo distinto.
- **El `log.warn` lleva el estado y el empleado.** Un rechazo de recuperación es
  el tipo de evento que se quiere poder contar: una ráfaga de ellos sobre cuentas
  pendientes es un intento de usar la puerta lateral que acaba de cerrarse.

### El efecto de lado: el flujo de Postman tenía que cambiar

La colección `auth-service` **dependía del defecto**. Su petición 5 pedía una
recuperación sobre la cuenta recién creada y la 6 usaba ese token para activarla.
Con la restricción aplicada, ese flujo deja de funcionar, y debe dejar de
funcionar.

Se reorganizó así:

| Petición | Qué hace |
|---|---|
| **5. Recuperacion rechazada antes de activar (401)** | Comprueba el rechazo y que el mensaje orienta al token de activación |
| **5.1 Ver el token de activacion en notificaciones (ADMIN)** | Toma el token del evento `usuario.creado`, que es su canal legítimo |
| **6. Establecer contraseña con el token (automático)** | Activa la cuenta con ese token |
| **14.1 Recuperacion aceptada con la cuenta ACTIVA (202)** | El camino correcto, ya con la cuenta activa |

El token se obtiene con un script, no a mano. La petición 5.1 ordena las
notificaciones por `processedAt` descendente y busca la del empleado, decodifica
el JWT —el payload es Base64url, no va cifrado— y lo guarda en la variable
`resetToken`. Sin ordenar se copiaba el token de una ejecución anterior, ya
expirado.

---

## 2.2 El healthcheck de `auth-service` apunta a `/health`

### El problema

El `healthcheck` de Compose consultaba `/auth/health`, que **responde 200 siempre,
sin mirar nada**. Con PostgreSQL caído, el contenedor seguía declarándose
saludable, Docker lo mantenía en servicio y los `depends_on:
condition: service_healthy` de los demás servicios lo daban por bueno. Un
healthcheck que no puede fallar no es un healthcheck.

### La decisión

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -f --silent http://localhost:$${SERVER_PORT:-8085}/health"]
```

`/health` es la sonda estandarizada del ecosistema: comprueba base de datos y
broker delegando en el `HealthEndpoint` de Actuator, y devuelve **503** si alguno
falla. `/auth/health` sigue existiendo por compatibilidad, pero ya no decide si
el contenedor está vivo.

Con esto, los siete servicios usan la misma forma de sonda, con el mismo formato
de respuesta:

```json
{ "status": "UP", "service": "auth-service",
  "checks": { "database": "UP", "messageBroker": "UP" } }
```

---

## 2.3 El perfil: cinco campos, `empleadoId` y archivado

### Los cinco campos canónicos

`telefono`, `direccion`, `ciudad`, `biografia` y `fechaCreacion`.

Los cuatro primeros son **datos del perfil, no del empleado**: `employees-service`
no los conoce ni los publica en sus eventos. De ahí que sean opcionales
(`String?`) y que los rellene el propio empleado con `PUT /perfiles/{id}`, que es
justo la operación que la regla de propiedad del ADR-008 le autoriza sobre su
propio recurso. Hacerlos obligatorios sería imposible: el perfil nace de un
evento que no los trae.

`fechaCreacion` es **cuándo se creó el perfil en el read model**, y es
deliberadamente distinta de `fechaIngreso`, que es cuándo entró el empleado a la
empresa. Las dos pueden diferir: el perfil se crea al procesar `empleado.creado`,
que puede ocurrir mucho después del ingreso si el empleado se registró tarde.
Confundirlas haría que un listado "por antigüedad" mintiera.

### `employeeId` → `empleadoId`

El modelo canónico del sistema usa nombres en español. Este campo era el único en
inglés, lo que obligaba a traducir en cada frontera —y esa traducción es donde
aparecen los errores silenciosos—.

Pero el nombre estaba **publicado**: lo devolvía `GET /perfiles/{id}`, servía
como `sortBy` y lo usaban las colecciones. Renombrarlo sin más rompe en silencio:
quien lea `employeeId` recibe `undefined`, no un error.

La regla aplicada —columna con el nombre canónico, respuesta con los dos nombres,
alias añadido en la frontera HTTP por un mapeador explícito, entradas que aceptan
solo el canónico— está en
**[ADR-013](decisions/ADR-013-renombrar-campos-publicados.md)**.

| | Antes | Ahora |
|---|---|---|
| Columna | `"employeeId"` | `"empleadoId"` |
| Índice | `EmployeeProfile_employeeId_key` | `EmployeeProfile_empleadoId_key` |
| Respuesta | `employeeId` | `empleadoId` + `employeeId` (alias `@deprecated`) |
| `sortBy` | `employeeId` | `empleadoId` |

La migración usa `ALTER TABLE ... RENAME COLUMN`, que conserva los datos y el
índice, y es **idempotente**: envuelta en un `DO $$` que comprueba si la columna
vieja sigue existiendo, de modo que aplicarla sobre una base ya migrada no falla.

### La marca de archivado

Un perfil retirado **no se borra** —el historial se conserva para auditoría— pero
tampoco debería arrastrarse en las consultas corrientes. `archivado Boolean
@default(false)` resuelve eso:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/perfiles?archivado=false"
```

Tres detalles:

- **Se escribe en la misma operación que el retiro.** `retire()` pone
  `estado: 'RETIRADO'` y `archivado: true` en un solo `update`. Si fueran dos
  escrituras, un fallo entre ellas dejaría un perfil retirado sin archivar, y
  nada lo volvería a intentar: el consumidor es idempotente y ya habría
  registrado el `eventId`.
- **No es un estado más, es una dimensión aparte.** `estado` dice qué le pasó al
  empleado; `archivado` dice si el perfil entra en las consultas corrientes. Un
  cliente puede filtrar por archivado sin tener que conocer la lista de estados
  ni su semántica.
- **La migración archiva los perfiles retirados que ya existían.** Un
  `UPDATE ... SET archivado = true WHERE estado = 'RETIRADO'` al final de la
  revisión. Sin eso, los retiros anteriores al cambio seguirían apareciendo entre
  los vigentes y el filtro daría resultados incorrectos justo para los datos
  históricos que la auditoría quiere revisar.

Un valor que no sea booleano en `?archivado=` devuelve **400**, no se ignora. Un
filtro mal escrito que se descarta en silencio devuelve una lista que parece
correcta y no lo es.

### La prueba ABAC de 403

La regla de propiedad —un `USER` puede leer cualquier perfil pero solo editar el
suyo— vive en el `AuthorizationFilter` del gateway (ADR-008) y **no tenía pruebas
automatizadas**. Se verificaba a mano con Postman, es decir: se verificaba cuando
alguien se acordaba.

Se añadieron **14 pruebas** en
`api-gateway/src/test/java/com/sistema/gateway/security/AuthorizationFilterTest.java`,
con `MockServerWebExchange`: construyen el intercambio en memoria y ejecutan el
filtro directamente, **sin abrir un socket ni levantar el contexto web**. Es una
decisión de diseño de la prueba, no una comodidad: una prueba que necesita un
puerto falla en CI por razones que no tienen que ver con la regla que verifica.

Cubren el caso central —`USER` que edita el perfil de otro → **403**— y el resto
de la matriz: el dueño editando el suyo, el `ADMIN` editando cualquiera, la
lectura permitida a todos, las rutas públicas sin token y las rutas protegidas
con un token de reset en lugar de un access token.

### Un defecto encontrado de paso

`profiles-service` publicaba sus eventos con `producer: 'employees-service'`,
contradiciendo su propio tipo. Nadie lo había notado porque el servicio se
ejecuta con `tsx`, que **no comprueba tipos**. Se corrigió junto con otros cuatro
errores de TypeScript preexistentes que el compilador encontró al pasarle el
servicio completo.

---

# Verificación

Todo se comprobó sobre el sistema levantado, no solo compilado.

| Suite | Comando | Resultado |
|---|---|---|
| `employees-service` | `./mvnw test` | **60 verdes**, 7 omitidas (integración), 14 no ejecutables en este equipo |
| `auth-service` | `./mvnw test` | **24 / 24**, incluidas 3 de la restricción de recuperación |
| `api-gateway` | `./mvnw test` | **14 / 14 ABAC**; las 7 de `GatewayTest` no ejecutables en este equipo |
| `departments-service` | `pytest` | **50 / 50**, 80,18 % con umbral del 70 % |
| `profiles-service` | `npx tsc --noEmit` | sin errores (4 preexistentes corregidos) |
| BDD de extremo a extremo | `./mvnw test` en `e2e-tests` | **39 / 39 escenarios** |
| Trazas en Zipkin | — | 48 spans · 7 servicios · 3 lenguajes |

Las pruebas de `recoverPassword` son las que cierran el hallazgo del Dev 2:
`recoverPasswordSeRechazaSiLaCuentaNoEstaActivada`,
`recoverPasswordSeRechazaSiLaCuentaEstaSuspendidaPorVacaciones` y
`recoverPasswordPublicaUsuarioRecuperacion` para el camino correcto. Una cuarta,
`recoverPasswordConEmailDesconocidoDevuelve404`, deja fijado que el 404 por correo
inexistente sigue siendo anterior a la nueva comprobación de estado.

> **Las 21 pruebas que no se pueden ejecutar en este equipo** son las que
> **abren un socket en loopback**: 14 en `employees-service`
> (`DepartmentCacheFallbackTest`, `DepartmentCircuitBreakerTest`, que levantan un
> servidor HTTP de prueba) y 7 en `api-gateway` (`GatewayTest`, que arranca Netty).
> Fallan con `java.io.IOException: Unable to establish loopback connection`, que
> es la misma restricción del entorno por la que Tomcat no arranca aquí: no es un
> fallo del código ni una regresión del lote, y no afecta a ninguna prueba de los
> cambios de Dev 1 o Dev 2.
>
> Es, de paso, la justificación de por qué las 14 pruebas ABAC se escribieron con
> `MockServerWebExchange`: **sí se ejecutan**, mientras las del mismo módulo que
> necesitan un puerto no. Una prueba que depende de un socket falla por razones
> ajenas a la regla que verifica.
>
> Las 7 omitidas de `employees-service` son las de integración con PostgreSQL
> real: se saltan solas si no hay base disponible.

> **Nota sobre el puerto 8080**: en este equipo Apache ocupa `127.0.0.1:8080` y
> Docker publica en `[::1]:8080`. La JVM resuelve IPv4 primero, así que la suite
> BDD recibía la página 404 de Apache. Se ejecuta con
> `BASE_URL="http://[::1]:8080"` mientras el conflicto no se resuelva en el host.

## Las colecciones de Postman

Las cinco se verificaron con Newman, **0 fallos**:

| Colección | Peticiones | Aserciones | Qué cambió |
|---|---|---|---|
| `auth-service` | 22 | 49 | Flujo reorganizado por la restricción de recuperación: 5 comprueba el 401, 5.1 toma el token del evento, 14.1 es el camino correcto con la cuenta ACTIVA |
| `perfiles-service` | 19 | 38 | Regenerada: 5 carpetas con los campos canónicos, `empleadoId`, el filtro de archivado y la matriz ABAC completa |
| `empleados-service` | 17 | 38 | Login ADMIN, ids parametrizados, y las peticiones 20/21/22 de la auditoría unificada |
| `departamentos-service` | 12 | 24 | Login ADMIN, el contrato que genera FastAPI (10) y el 409 con empleados asociados (11) |
| `observabilidad` | 18 | 39 | Sin cambios de este lote |

Dos arreglos que no venían del lote pero bloqueaban la verificación: **ninguna de
las colecciones de servicio enviaba el JWT**, así que `departamentos` fallaba
17/17 aserciones y `empleados` 15/36 **desde el Reto 5**, cuando el gateway
empezó a exigir token. Nadie lo había notado porque las colecciones no se
ejecutaban completas. Además había aserciones caducadas —por ejemplo, una que
exigía exactamente 10 claves en la respuesta de un empleado, que ahora trae 12— y
`perfiles` apuntaba a una variable `{{perfilesBaseUrl}}` que no estaba definida.

### Orden de ejecución

Las colecciones son **secuenciales y con estado**: cada una crea sus propios datos
y guarda ids en variables de colección. Hay que ejecutarlas completas y en orden,
con el entorno `system-hr` seleccionado.

```bash
newman run postman/auth-service.postman_collection.json -e postman/system-hr.postman_environment.json
```

La primera petición de cada colección es un **login de ADMIN y es obligatoria**:
sin el token, el gateway responde 401 a todo lo demás.

---

# Resumen de archivos

| Dev | Archivo | Cambio |
|---|---|---|
| 1 | `employees-service` · `model/Employee.java` | `fechaRetiro` → `LocalDateTime` |
| 1 | `employees-service` · `db/migration/V3__fecha_retiro_a_timestamp.sql` | nuevo |
| 1 | `employees-service` · `repository/EmployeeRepository.java` | consulta por rango de `LocalDateTime` |
| 1 | `employees-service` · `service/EmployeeService.java` | auditoría unificada en `findAll`, validación de rango |
| 1 | `employees-service` · `controller/EmployeeController.java` | `desde`/`hasta` en `/empleados`, alias `@Deprecated` |
| 1 | `employees-service` · `events/EmployeeEventData.java` | publica la fecha almacenada |
| 1 | `departments-service/**` | reconstruido en Python |
| 1 | `api-gateway` · `application.yml` | `SetPath=/openapi.json` |
| 2 | `auth-service` · `service/AuthService.java` | `recoverPassword` exige ACTIVA |
| 2 | `docker-compose.yml` | healthcheck de auth a `/health` |
| 2 | `profiles-service` · `prisma/schema.prisma` | 5 campos, `empleadoId`, `archivado` |
| 2 | `profiles-service` · `prisma/migrations/2026…_campos_canonicos_y_archivado/` | nuevo, idempotente |
| 2 | `profiles-service` · `mappers/profile.mapper.ts` | alias `employeeId` en la frontera HTTP |
| 2 | `profiles-service` · `repositories/profile.repository.ts` | archivado en la misma escritura del retiro |
| 2 | `profiles-service` · `controllers/profile.controller.ts` | filtro `?archivado=` con 400 |
| 2 | `profiles-service` · `messaging/employee-event.publisher.ts` | `producer` correcto |
| 2 | `api-gateway` · `security/AuthorizationFilterTest.java` | 14 pruebas ABAC |
| — | `docs/decisions/ADR-012`, `ADR-013` | decisiones registradas |
