# Guía de pruebas del Reto 5 — Seguridad y Control de Acceso con JWT

Orden exacto para demostrar todo lo que pide el enunciado, con el nombre de cada
carpeta y request de Postman y su ruta HTTP real.

**Tiempo estimado:** 15 minutos de principio a fin.

---

## Índice

- [Paso 0 — Levantar el sistema en Docker](#paso-0--levantar-el-sistema-en-docker)
- [Paso 1 — Configurar Postman](#paso-1--configurar-postman)
- [El único paso manual: el token de activación](#el-único-paso-manual-el-token-de-activación)
- [Orden de ejecución](#orden-de-ejecución)
  - [Bloque A — auth-service](#bloque-a--colección-auth-service-reto-5)
  - [Bloque B — Seguridad y RBAC](#bloque-b--colección-seguridad-y-rbac-reto-5)
  - [Bloque C — Ciclo de vida y scheduler](#bloque-c--colección-ciclo-de-vida-de-la-cuenta-y-scheduler-reto-5)
- [Evidencia en los logs](#evidencia-en-los-logs)
- [Swagger UI](#swagger-ui)
- [Pruebas automatizadas](#pruebas-automatizadas)
- [Mapa de criterios de evaluación](#mapa-de-criterios-de-evaluación)
- [Si algo falla](#si-algo-falla)

---

## Paso 0 — Levantar el sistema en Docker

### 0.1 Resolver el puerto 8080

El API Gateway es el **único puerto publicado al host**. Si otro proceso lo
ocupa (en la máquina de desarrollo lo ocupaba Apache), hay dos caminos:

| Opción | Qué hacer | `gatewayBaseUrl` |
|---|---|---|
| **A** (recomendada para la sustentación) | Detener el proceso que ocupa el 8080 | `http://localhost:8080` |
| **B** | Poner `GATEWAY_PORT=8090` en el `.env` | `http://localhost:8090` |

Para saber quién lo ocupa:

```powershell
netstat -ano | findstr ":8080" | findstr "LISTENING"
```

### 0.2 Preparar el entorno

```powershell
cd "C:\Users\Milton Gómez\Universidad\Microservicios\microservicios\System_HR"
```

Si no existe el `.env`:

```powershell
copy .env.example .env
```

El `.env.example` ya trae el `JWT_SECRET` académico y el administrador semilla.
**Si `JWT_SECRET` no está definido, el `docker compose up` falla con un mensaje
claro** en lugar de arrancar con un secreto por defecto conocido.

### 0.3 Levantar desde cero

> **Importante:** arranque con base limpia. Las colecciones crean empleados con
> ids fijos (`E501`, `E601`, `E602`, `E701`, `E702`) y esperan `201`. Si ya
> existen de una corrida anterior, responderán `400` por duplicado.

```powershell
docker compose down -v
```

```powershell
docker compose up --build -d
```

La primera vez tarda **20 minutos o más**: construye siete imágenes y descarga
las dependencias Maven de los tres servicios Java. Las siguientes veces, con
caché, es cuestión de un minuto.

> Si el build falla con `Remote host terminated the handshake`, es saturación de
> red al descargar dependencias en paralelo, no un error de código. Repite el
> comando: la caché conserva lo ya descargado. Si se repite, constrúyelos en
> serie:
> ```powershell
> docker compose build auth-service
> docker compose build api-gateway
> docker compose build employees-service
> docker compose up -d
> ```

### 0.4 Confirmar que arrancó bien

```powershell
docker compose ps
```

Deben aparecer **14 contenedores `(healthy)`**:

```
NAME                                  STATUS
system_hr-api-gateway-1               Up (healthy)
system_hr-auth-service-1              Up (healthy)
system_hr-database-departamentos-1    Up (healthy)
system_hr-database-empleados-1        Up (healthy)
system_hr-database-notificaciones-1   Up (healthy)
system_hr-database-perfiles-1         Up (healthy)
system_hr-db-auth-1                   Up (healthy)
system_hr-db-vacaciones-1             Up (healthy)
system_hr-departments-service-1       Up (healthy)
system_hr-employees-service-1         Up (healthy)
system_hr-notifications-service-1     Up (healthy)
system_hr-profiles-service-1          Up (healthy)
system_hr-rabbitmq-1                  Up (healthy)
system_hr-vacations-service-1         Up (healthy)
```

El orden en que aparecen los `Healthy` durante el `up` es la evidencia del
**arranque ordenado**: primero las bases de datos y RabbitMQ, después los
servicios que dependen de ellos, y el gateway al final.

Confirma que el `auth-service` creó su administrador:

```powershell
docker compose logs auth-service | Select-String "Administrador semilla"
```

```
[AUTH] Administrador semilla creado | empleadoId='admin' | email='admin@empresa.com' | rol=ADMIN
```

---

## Paso 1 — Configurar Postman

### 1.1 Importar

**Import** → arrastra estos cuatro archivos de la carpeta `postman/`:

| Archivo | Qué es |
|---|---|
| `system-hr.postman_environment.json` | Environment (variables compartidas) |
| `auth-service.postman_collection.json` | Bloque A |
| `seguridad-rbac.postman_collection.json` | Bloque B |
| `ciclo-vida-cuenta.postman_collection.json` | Bloque C |

### 1.2 Seleccionar el environment

Arriba a la derecha, elige **System HR - local**.

Si usas el puerto 8090, edítalo y cambia `gatewayBaseUrl` a
`http://localhost:8090`.

> Postman usa la columna **Current value**, no *Initial value*. Si solo cambias
> la inicial, la petición seguirá yendo al 8080.

El environment ya trae las credenciales del administrador semilla
(`adminUsuario`, `adminPassword`), así que el login funciona sin tocar nada.

---

## El único paso manual: el token de activación

Tres requests están marcados **[MANUAL]**. No es un atajo pendiente: es
consecuencia del diseño correcto.

Cuando se crea un empleado, el token de activación **nunca viaja en la respuesta
HTTP**. Se publica como evento `usuario.creado` y el servicio de notificaciones
simula el correo. Una API que devolviera el token de activación en el `POST`
sería un agujero de seguridad: cualquiera que pudiera crear empleados podría
tomar posesión de sus cuentas.

Por eso hay que sacarlo del log:

```powershell
docker compose logs notifications-service | Select-String "NOTIFICACIÓN"
```

```
[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: laura.mendez@empresa.com | Mensaje: "Para
establecer su contraseña use el siguiente enlace:
https://app.empresa.com/reset?token=eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzeXN0ZW0t..."
```

Copia **solo lo que va después de `token=`** y pégalo en la variable de colección
`resetToken`:

> Click derecho en la colección → **Edit** → pestaña **Variables** → fila
> `resetToken` → columna **Current value**.

Si hay varios tokens en el log (porque creaste varios empleados), usa el **último**.

---

## Orden de ejecución

### Bloque A — Colección `auth-service (Reto 5)`

Demuestra el **criterio 1** (servicio de autenticación) y la separación entre los
dos tipos de token.

La colección no tiene carpetas: los 15 requests van en orden plano.

| # | Request | Método y ruta | Resultado esperado |
|---|---|---|---|
| 1 | `1. Login del administrador semilla` | `POST /auth/login` | **200** · `rol: ADMIN` · guarda `adminToken` |
| 2 | `2. Login con contraseña incorrecta` | `POST /auth/login` | **401** · `"Credenciales inválidas"` |
| 3 | `2.1 Precondición: departamento IT (ADMIN)` | `POST /departamentos` | **201** (o 400/409 si ya existe) |
| 4 | `3. Crear empleado (ADMIN) para obtener su cuenta` | `POST /empleados` | **201** · dispara `empleado.creado` |
| 5 | `4. Login antes de activar la cuenta` | `POST /auth/login` | **401** · `"no ha sido activada"` |
| 6 | `5. Solicitar recuperación de contraseña` | `POST /auth/recover-password` | **202** · publica `usuario.recuperacion` |
| — | **⏸ Pausa: copia el `resetToken` del log** | | |
| 7 | `6. Establecer contraseña con el token` | `POST /auth/reset-password` | **200** · cuenta `ACTIVA` |
| 8 | `7. Contraseña que no cumple la política` | `POST /auth/reset-password` | **400** · `"al menos 8 caracteres"` |
| 9 | `8. Un Access JWT no sirve como token de reset` | `POST /auth/reset-password` | **401** · `"no es de restablecimiento"` |
| 10 | `9. Un token de reset no abre recursos` | `GET /empleados` | **401** · `"no es válido como token de acceso"` |
| 11 | `10. Login del empleado ya activado` | `POST /auth/login` | **200** · `rol: USER` · `sub = E501` |
| 12 | `11. Cambiar la propia contraseña (USER)` | `POST /auth/change-password` | **200** |
| 13 | `12. La contraseña antigua ya no sirve` | `POST /auth/login` | **401** |
| 14 | `13. Login con la contraseña nueva` | `POST /auth/login` | **200** |
| 15 | `14. Recuperación con email desconocido` | `POST /auth/recover-password` | **404** |

**Cómo correrlo:** pulsa **Run collection** y deja que llegue al request `5.`. Luego copia el
token del log, pégalo en `resetToken`, y vuelve a correr la colección completa:
los requests 1 a 5 son idempotentes salvo el `3.`, que dará `400` por duplicado la
segunda vez (es esperado y no afecta al resto).

**Alternativa más limpia:** ejecuta los requests `1.` a `5.` uno por uno con el botón
**Send**, copia el token, y sigue con el `6.` en adelante.

### Lo que conviene destacar del bloque A

Los requests **8 y 9** son la pareja más interesante. Los dos tipos de token se
firman con el **mismo secreto**, así que lo único que los distingue es el claim
`type: "RESET_PASSWORD"`. La validación va en ambas direcciones:

- El `auth-service` exige ese claim para aceptar un reset → un token de acceso no
  sirve para cambiar una contraseña.
- El gateway rechaza los tokens que lo llevan → un token de recuperación enviado
  por correo no abre ningún recurso.

Sin esa comprobación, un enlace de recuperación en la bandeja de entrada
equivaldría a una sesión completa.

---

### Bloque B — Colección `Seguridad y RBAC (Reto 5)`

Demuestra los **criterios 2** (validación de token) y **3** (control de acceso).
21 requests en 6 carpetas.

#### Carpeta `0. Peticiones sin token (401)`

Se puede ejecutar sin preparación previa. Es el **criterio 2** completo.

| Request | Método y ruta | Esperado |
|---|---|---|
| `0.1 GET /empleados sin cabecera Authorization` | `GET /empleados` | **401** + cabecera `WWW-Authenticate: Bearer` |
| `0.2 GET /empleados con token alterado` | `GET /empleados` | **401** |
| `0.3 Cabeceras de identidad falsificadas por el cliente` | `GET /empleados` | **401** |

El `0.2` envía un token cuyo payload se autoproclama `ADMIN` pero con la firma
falsificada. El gateway lo rechaza al verificar la firma HS256: sin el secreto no
se puede forjar un token válido.

El `0.3` es el más sutil y vale la pena explicarlo: los microservicios confían en
las cabeceras `X-Auth-User` y `X-Auth-Role` que les manda el gateway. Para que esa
confianza sea segura, **el gateway las borra si llegan del cliente** antes de
validar. Sin eso, este request habría entrado como administrador.

#### Carpeta `1. Preparación con rol ADMIN`

| Request | Método y ruta | Esperado |
|---|---|---|
| `1.1 Login ADMIN` | `POST /auth/login` | **200** · guarda `adminToken` |
| `1.2 Crear departamento IT (ADMIN)` | `POST /departamentos` | **201** (o 400/409 si ya existe) |
| `1.3 Crear empleado propio E601 (ADMIN)` | `POST /empleados` | **201** |
| `1.4 Crear empleado ajeno E602 (ADMIN)` | `POST /empleados` | **201** |

El `1.3` imprime en la consola de Postman el comando para obtener el token.

#### ⏸ Pausa: copia el `resetToken` de E601 del log

#### Carpeta `2. Activación y login del rol USER`

| Request | Método y ruta | Esperado |
|---|---|---|
| `2.1 Establecer contraseña de E601` | `POST /auth/reset-password` | **200** |
| `2.2 Login de E601 (rol USER)` | `POST /auth/login` | **200** · `rol: USER` · guarda `userToken` |

#### Carpeta `3. Lo que SÍ puede hacer un USER`

| Request | Método y ruta | Esperado |
|---|---|---|
| `3.1 GET /empleados con token válido (200)` | `GET /empleados` | **200** |
| `3.2 GET /departamentos (lectura permitida)` | `GET /departamentos` | **200** |
| `3.3 PUT /perfiles/E601 — su propio perfil (200)` | `PUT /perfiles/E601` | **200** |
| `3.4 POST /auth/change-password (200)` | `POST /auth/change-password` | **200** |

#### Carpeta `4. Lo que NO puede hacer un USER (403)`

| Request | Método y ruta | Esperado |
|---|---|---|
| `4.1 DELETE /empleados/E602 (403)` | `DELETE /empleados/E602` | **403** |
| `4.2 POST /departamentos (403)` | `POST /departamentos` | **403** |
| `4.3 PUT /perfiles/E602 — perfil ajeno (403)` | `PUT /perfiles/E602` | **403** |
| `4.4 POST /vacaciones (403)` | `POST /vacaciones` | **403** |

#### Carpeta `5. Lo que SÍ puede hacer un ADMIN`

| Request | Método y ruta | Esperado |
|---|---|---|
| `5.1 PUT /perfiles/E602 con token ADMIN (200)` | `PUT /perfiles/E602` | **200** |
| `5.2 DELETE /empleados/E602 con token ADMIN` | `DELETE /empleados/E602` | **200** o **204** |
| `5.3 Auditoría: el retirado aparece con su fechaRetiro` | `GET /empleados?estado=RETIRADO` | **200** · E602 con `fechaRetiro` |
| `5.4 GET /empleados con token ADMIN (200)` | `GET /empleados` | **200** |

### Lo que conviene destacar del bloque B

**La comparación `3.3` contra `4.3`.** Mismo token (el de E601), mismo verbo
(`PUT`), misma ruta salvo el id. Uno da **200** y el otro **403**. Lo único que
cambia es quién es dueño del dato:

```
si rol == ADMIN                                  → permitir
si rol == USER y método es de lectura            → permitir
si rol == USER y recurso.empleadoId == token.sub → permitir
en cualquier otro caso                           → 403 Forbidden
```

Eso es **ABAC**, no RBAC: la autorización deja de depender solo del rol y empieza
a depender del dueño del recurso. Es lo que permite que cada empleado gestione su
perfil sin darle permisos de administrador.

**La comparación `4.3` contra `5.1`.** El mismo request exacto, con un token de
rol distinto: **403** con `USER`, **200** con `ADMIN`.

**Lectura permitida para ambos roles.** `3.1` y `5.4` consultan `GET /empleados`
con tokens de `USER` y `ADMIN`, respectivamente; ambos deben recibir **200**.

**Por qué 403 y no 401.** El `401` significa "no sé quién eres" y lo emite el
filtro de autenticación. El `403` significa "sé quién eres y no puedes" y lo emite
el de autorización. El enunciado pide esa distinción explícitamente.

---

### Bloque C — Colección `Ciclo de vida de la cuenta y scheduler (Reto 5)`

Demuestra el **criterio 4**, el de mayor peso (1.5 puntos). 16 requests en 4
carpetas.

> **Requiere base limpia.** Usa los ids `E701` y `E702`. Si ya existen, haz
> `docker compose down -v && docker compose up -d` antes de empezar.

#### Carpeta `1. Preparación`

| Request | Método y ruta | Esperado |
|---|---|---|
| `1.1 Login ADMIN y fecha de hoy` | `POST /auth/login` | **200** · calcula la fecha de hoy |
| `1.2 Departamento IT` | `POST /departamentos` | **201** (o 400/409) |
| `1.3 Crear E701 → dispara empleado.creado` | `POST /empleados` | **201** |
| `1.4 [MANUAL] Activar cuenta de E701` | `POST /auth/reset-password` | **200** |

El `1.1` guarda la fecha de hoy en la variable `hoy` y la usan los períodos de
vacaciones. Es la **estrategia nº 1** que sugiere el enunciado:
`fechaInicio = fechaFin = hoy`.

⏸ Entre el `1.3` y el `1.4`, copia el `resetToken` del log.

#### Carpeta `2. login OK → vacaciones inician → login falla`

| Request | Método y ruta | Esperado |
|---|---|---|
| `2.1 Login de E701 funciona` | `POST /auth/login` | **200** ← **login OK** |
| `2.2 Programar vacaciones de hoy a hoy (ADMIN)` | `POST /vacaciones` | **201** · estado `PROGRAMADA` · guarda `periodoId` |
| `2.3 Forzar inicio del período (ADMIN)` | `POST /vacaciones/{periodoId}/forzar-inicio` | **200** · estado `EN_CURSO` |
| `2.4 Login de E701 ahora FALLA (401)` | `POST /auth/login` | **401** ← **login falla** |

El mensaje del `2.4` es literal:

```
"La cuenta está suspendida temporalmente porque el empleado se encuentra de vacaciones"
```

La contraseña es correcta. Lo que bloquea el acceso es el **estado de la cuenta**,
que cambió por un evento sin que nadie tocara el `auth-service`.

#### Carpeta `3. vacaciones terminan → login OK`

| Request | Método y ruta | Esperado |
|---|---|---|
| `3.1 Forzar fin del período (ADMIN)` | `POST /vacaciones/{periodoId}/forzar-fin` | **200** · estado `FINALIZADA` |
| `3.2 Login de E701 vuelve a funcionar (200)` | `POST /auth/login` | **200** ← **login OK** |

Con esto queda demostrada la cadena completa que exige el enunciado:

```
login OK → vacaciones inician → login falla → vacaciones terminan → login OK
```

#### Carpeta `4. CASO BORDE: retiro durante vacaciones`

Es el escenario que el enunciado evalúa de forma explícita.

| Request | Método y ruta | Esperado |
|---|---|---|
| `4.1 Crear E702` | `POST /empleados` | **201** |
| `4.2 Programar y arrancar sus vacaciones` | `POST /vacaciones` | **201** · guarda `bordePeriodoId` |
| `4.3 Forzar inicio → cuenta SUSPENDIDA_TEMPORAL` | `POST /vacaciones/{bordePeriodoId}/forzar-inicio` | **200** · `EN_CURSO` |
| `4.4 Retirar a E702 estando de vacaciones (ADMIN)` | `DELETE /empleados/E702` | **200** o **204** |
| `4.5 Forzar fin: la cuenta NO debe reactivarse` | `POST /vacaciones/{bordePeriodoId}/forzar-fin` | **200** · `FINALIZADA` |
| `4.6 El login de E702 sigue fallando` | `POST /auth/login` | **401** |

El `4.5` es el momento clave: **el período de vacaciones sí termina** —es un hecho
del calendario— **pero la cuenta no vuelve**. La distinción la hace el estado:

```java
public boolean esReactivablePorVacaciones() {
    return this == SUSPENDIDA_TEMPORAL;
}
```

`DESACTIVADA_PERMANENTE` devuelve `false`. Con un simple booleano `activa` esto
sería imposible: el manejador no podría saber si el `false` vino de unas
vacaciones o de un retiro.

Confírmalo en el log (es la mejor evidencia de este punto):

```powershell
docker compose logs auth-service | Select-String "NO se reactiva"
```

```
[AUTH] NO se reactiva: estado 'DESACTIVADA_PERMANENTE' no es reactivable por
       vacaciones (caso borde retiro durante vacaciones) | empleado='E702'
```

### Sobre el scheduler automático

Los requests `forzar-inicio` y `forzar-fin` son **endpoints de desarrollo**
protegidos con rol `ADMIN`. Existen porque esperar un minuto por transición es
incómodo en una sustentación, y porque la `fechaFin` es **inclusiva**: unas
vacaciones que terminan hoy siguen vigentes hoy, así que el scheduler automático
no las finalizaría hasta mañana.

El scheduler **sí funciona solo**, cada minuto. Para demostrarlo sin forzar nada:
ejecuta hasta el `2.2`, espera un minuto y mira:

```powershell
docker compose logs vacations-service | Select-String "SCHEDULER"
```

```
[VACATIONS] [SCHEDULER] Activo con expresión '* * * * *' (configurable en VACACIONES_CRON)
[VACATIONS] [SCHEDULER] Período <uuid> EN_CURSO | empleado: E701 | 2026-10-20 → 2026-10-20
[VACATIONS] [SCHEDULER] Ciclo completado | iniciadas: 1 | finalizadas: 0
```

Verás que el período pasó a `EN_CURSO` **sin que tú ejecutaras el `2.3`**, y que
el login ya falla.

---

## Evidencia en los logs

Esto es lo que demuestra que la cadena de eventos ocurre sola, que es el corazón
del criterio 4.

### La cadena completa del onboarding

```powershell
docker compose logs auth-service | Select-String "RECIBIDO|PUBLICADO|Cuenta"
```

```
[AUTH] [RECIBIDO] empleado.creado | eventId='c43eeb2b-...' | empleado='E701'
[AUTH] Cuenta creada en PENDIENTE_ACTIVACION con rol USER | empleado='E701'
[AUTH] [PUBLICADO] usuario.creado | eventId='e75a2ef5-...' | empleado='E701'
[AUTH] Cuenta SUSPENDIDA_TEMPORAL por vacaciones | empleado='E701'
[AUTH] [PUBLICADO] cuenta.desactivada | empleado='E701'
[AUTH] Cuenta ACTIVA de nuevo tras vacaciones | empleado='E701'
[AUTH] [PUBLICADO] cuenta.activada | empleado='E701'
```

### Las notificaciones simuladas

```powershell
docker compose logs notifications-service | Select-String "NOTIFICACIÓN"
```

```
[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: ... | Mensaje: "Para establecer su contraseña..."
[NOTIFICACIÓN] Tipo: CUENTA | Para: ... | Mensaje: "Su cuenta fue desactivada temporalmente. Motivo: VACACIONES."
[NOTIFICACIÓN] Tipo: CUENTA | Para: ... | Mensaje: "Bienvenido de regreso. Su cuenta fue reactivada..."
```

### Los rechazos del gateway

```powershell
docker compose logs api-gateway | Select-String "401|403"
```

```
[GATEWAY] 401 sin token | GET /empleados
[GATEWAY] 401 token rechazado | GET /empleados | causa='Token inválido o expirado'
[GATEWAY] 403 | empleado='E601' | rol='USER' | DELETE /empleados/E602
```

### Las colas de RabbitMQ

```
http://localhost:15672
```
Usuario `admin`, contraseña `admin`. En **Queues** debe aparecer `auth.queue`
junto a las tres del Reto 4, todas con binding al exchange `hr.events`.

---

## Swagger UI

Para probar la API sin Postman, con el esquema `BearerAuth`:

```
http://localhost:8080/swagger-ui.html
```

Una sola interfaz con un desplegable por servicio:

1. Elige **1. auth-service** en el desplegable de arriba.
2. Ejecuta `POST /auth/login` con el administrador semilla.
3. Copia el valor de `token` de la respuesta.
4. Pulsa **Authorize**, pega el token y confirma.
5. Cambia a **2. employees-service** y ejecuta cualquier operación: ya va
   autenticada.

---

## Pruebas automatizadas

```powershell
cd services\auth-service
```

```powershell
.\mvnw.cmd clean test
```

Resultado esperado:

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  -- AccountLifecycleTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- AuthEndpointsTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los 20 tests usan H2 en memoria y un mock del publisher, así que **no necesitan
Docker ni PostgreSQL ni RabbitMQ**: se pueden correr en cualquier momento. Entre
ellos está `retiroDuranteVacacionesImpideLaReactivacionAlFinalizarElPeriodo`, de
modo que si alguien rompe la lógica del caso borde, el test lo detecta.

---

## Mapa de criterios de evaluación

| # | Criterio | Valor | Dónde se demuestra |
|---|---|---|---|
| 1 | Servicio de autenticación | 1.0 | **Bloque A** completo. Hash BCrypt visible en el test `laPasswordSeGuardaHasheadaYNoEnClaro`; token de activación con expiración en los requests 5–8 |
| 2 | Validación de token | 0.5 | **Bloque B, carpeta 0**: los tres casos de `401` |
| 3 | Control de acceso (RBAC + propiedad) | 1.0 | **Bloque B, carpetas 3, 4 y 5**. La pareja `3.3` / `4.3` es la propiedad del recurso; la pareja `4.3` / `5.1` es el RBAC |
| 4 | Ciclo de vida por eventos + scheduler | 1.5 | **Bloque C** completo. La cadena en las carpetas 2 y 3; el caso borde en la 4; el scheduler en los logs de `vacations-service` |
| 5 | Gestión de variables de entorno | 0.5 | `.env.example` y `docker-compose.yml`. El `JWT_SECRET` se inyecta por entorno a los dos contenedores que lo necesitan y el arranque falla si no está |
| 6 | Documentación e integración | 0.5 | Sistema levantable con un comando; Swagger con `BearerAuth`; diagrama de secuencia en el README global (sección 15); limitación del scheduler documentada (sección 16) |

### Documentos de respaldo

| Documento | Qué contiene |
|---|---|
| [`README.md`](../README.md) secciones 11–20 | Cómo obtener el token, estrategia de validación y su justificación, reglas RBAC, diagrama de secuencia, scheduler y su limitación, evidencias reales |
| [`ADR-008`](decisions/ADR-008-validacion-jwt-en-el-gateway.md) | Por qué la validación va en el gateway y no en cada servicio |
| [`ADR-009`](decisions/ADR-009-estados-de-cuenta.md) | Por qué el estado es una enumeración y no un booleano |
| [`services/auth-service/README.md`](../services/auth-service/README.md) | Modelo de datos, los dos tipos de token, ciclo de vida, decisiones y limitaciones |

---

## Si algo falla

| Síntoma | Causa | Solución |
|---|---|---|
| `docker compose up` falla con `JWT_SECRET` | No existe el `.env` | `copy .env.example .env` |
| `port is already allocated` | Otro proceso en el 8080 | Pararlo, o `GATEWAY_PORT=8090` en el `.env` |
| Todo responde `401` incluso con token | `gatewayBaseUrl` apunta al puerto equivocado, o editaste *Initial value* en vez de *Current value* | Revisa el environment |
| `POST /empleados` responde `400` por duplicado | Los ids ya existen de una corrida anterior | `docker compose down -v && docker compose up -d` |
| `POST /empleados` responde `400` por departamento | No se creó el departamento `IT` | Ejecuta el request `1.2` de su bloque |
| El request `[MANUAL]` falla | `resetToken` vacío o caducado | Vuelve a sacarlo del log; expira en 1 hora |
| `auth-service` no arranca | `JWT_SECRET` con menos de 32 caracteres | Es deliberado: aborta antes que firmar con clave débil |
| El build falla con `terminated the handshake` | Saturación de red bajando dependencias | Repite el comando, o construye en serie (ver paso 0.3) |
| `forzar-inicio` responde `404` | El `periodoId` está vacío porque el `POST /vacaciones` anterior falló | Revisa la respuesta del request previo |

### Limitación conocida que conviene mencionar

El scheduler corre **en cada instancia** del `vacations-service`. Con una sola
instancia funciona. Si el servicio se escalara a N, las N dispararían el mismo
evento. Hay dos defensas complementarias:

1. **Deduplicación en el consumidor** (ya implementada): el `auth-service`
   descarta el evento repetido por su `eventId`. Mitiga el daño, pero no evita el
   trabajo duplicado.
2. **Coordinación en el productor** (no implementada): garantizar que solo una
   instancia ejecute el job, con un lock distribuido como **ShedLock**.

En este reto se acepta la versión de instancia única. Está documentado en la
sección 16 del README global.
