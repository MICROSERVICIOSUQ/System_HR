# auth-service

Proveedor de identidad del sistema. Verifica credenciales, emite Access JWT y
gestiona el ciclo de vida de las cuentas a partir de los eventos del ecosistema.

Java 21, Spring Boot 3.4, PostgreSQL con Flyway, RabbitMQ y firma HS256 con jjwt.

---

## Tabla de contenido

- [Arquitectura](#arquitectura)
- [Modelo de datos](#modelo-de-datos)
- [API](#api)
- [Los dos tipos de token](#los-dos-tipos-de-token)
- [Ciclo de vida de la cuenta](#ciclo-de-vida-de-la-cuenta)
- [El caso borde del retiro durante vacaciones](#el-caso-borde-del-retiro-durante-vacaciones)
- [Configuración](#configuración)
- [Pruebas](#pruebas)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Limitaciones conocidas](#limitaciones-conocidas)

---

## Arquitectura

```
   HTTP (vía api-gateway)                 RabbitMQ (auth.queue)
          │                                        │
          ▼                                        ▼
┌─────────────────────┐              ┌──────────────────────────┐
│ AuthController      │              │ HrEventListener          │
└─────────┬───────────┘              └────────────┬─────────────┘
          ▼                                       ▼
┌─────────────────────┐              ┌──────────────────────────┐
│ AuthService         │              │ AccountLifecycleService  │
│ login / reset /     │              │ alta, suspensión,        │
│ recover / change    │              │ reactivación, baja       │
└─────────┬───────────┘              └────────────┬─────────────┘
          │        ┌──────────────────────────────┘
          ▼        ▼
┌─────────────────────┐    ┌──────────────────┐    ┌─────────────────────┐
│ UserAccountRepo     │    │ JwtService       │    │ AuthEventPublisher  │
│ (JPA / PostgreSQL)  │    │ firma y valida   │    │ → hr.events         │
└─────────────────────┘    └──────────────────┘    └─────────────────────┘
```

| Componente | Responsabilidad |
|---|---|
| `AuthController` | Traduce HTTP ↔ dominio. Lee la identidad de `X-Auth-User` |
| `AuthService` | Los cuatro casos de uso de credenciales |
| `AccountLifecycleService` | Transiciones de estado por eventos, con idempotencia |
| `JwtService` | Emite y verifica Access JWT y Reset JWT |
| `PasswordPolicy` | Mínimo 8 caracteres, con letra y dígito |
| `AuthEventPublisher` | Publica los cuatro eventos salientes |
| `HrEventListener` | Consume `auth.queue` |

**Este servicio no valida JWT en las peticiones entrantes.** Eso lo hace el
api-gateway una sola vez para todo el ecosistema y propaga la identidad en
`X-Auth-User` / `X-Auth-Role`. Ver [ADR-008](../../docs/decisions/ADR-008-validacion-jwt-en-el-gateway.md).

---

## Modelo de datos

Tabla `user_accounts`:

| Columna | Tipo | Notas |
|---|---|---|
| `empleado_id` | `VARCHAR` **PK** | Es el claim `sub` del JWT y la clave de la comprobación de propiedad |
| `email` | `VARCHAR` **UNIQUE** | Credencial de login y destinatario de las notificaciones |
| `password_hash` | `VARCHAR` nullable | **Hash BCrypt**. `NULL` mientras la cuenta esté pendiente de activación |
| `role` | `VARCHAR` | `ADMIN` · `USER`, con `CHECK` |
| `estado` | `VARCHAR` | Los cuatro estados, con `CHECK` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

La clave primaria es el `empleadoId` y no un UUID interno porque es el valor que
el gateway compara contra la ruta para decidir si un `USER` puede escribir sobre
un recurso (`recurso.empleadoId == token.sub`).

Tabla `eventos_procesados`: idempotencia de los consumidores, con el `id` del
envelope como clave. Mismo patrón que los servicios del Reto 4.

---

## API

Base: `http://localhost:8080/auth` (a través del gateway).

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| `POST` | `/auth/login` | **Público** | Verifica credenciales y devuelve el Access JWT |
| `POST` | `/auth/recover-password` | **Público** | Publica `usuario.recuperacion` con un token nuevo |
| `POST` | `/auth/reset-password` | **Público** | Establece la contraseña con el token recibido |
| `POST` | `/auth/change-password` | JWT (ADMIN o USER) | Cambia la propia contraseña |
| `GET` | `/auth/health` | **Público** | Healthcheck de Docker |

`/auth/reset-password` es público a propósito: su autorización no viene de una
sesión previa sino del token de un solo propósito que llega en el cuerpo. Exigir
un Access JWT ahí haría imposible la activación inicial, que es justo el momento
en el que el usuario todavía no puede iniciar sesión.

### POST /auth/login

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"juan.perez@empresa.com","password":"Secreta123"}'
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tipo": "Bearer",
  "expiraEnSegundos": 3600,
  "empleadoId": "E001",
  "rol": "USER"
}
```

El campo `usuario` acepta el email o el `empleadoId`: el enunciado habla de
"usuario y contraseña" y el flujo de prueba usa el correo.

### Catálogo de errores

| HTTP | Cuándo |
|---|---|
| `400` | Campos ausentes, o contraseña que no cumple la política |
| `401` | Credenciales incorrectas; cuenta pendiente, suspendida o retirada; token inválido, expirado o de tipo incorrecto |
| `404` | No existe cuenta para ese email o identificador |
| `503` | La base de datos no responde |

El mensaje de credenciales es genérico (`Credenciales inválidas`) tanto si el
usuario no existe como si la contraseña es incorrecta: distinguirlos permitiría
enumerar cuentas válidas.

---

## Los dos tipos de token

Ambos se firman con **HS256** y el mismo secreto (`JWT_SECRET`), pero tienen
propósitos distintos y no son intercambiables.

**Access JWT** — lo devuelve `/auth/login`:

```json
{ "sub": "E001", "role": "USER", "iat": 1712345678, "exp": 1712349278 }
```

**Reset JWT** — se genera al dar de alta un empleado o al pedir recuperación
(Opción A del enunciado: *stateless*, no se guarda en base de datos):

```json
{ "sub": "E001", "type": "RESET_PASSWORD", "iat": 1712345678, "exp": 1712349278 }
```

El claim `type` no es decorativo. Como los dos tokens llevan la misma firma, sin
él un token de recuperación enviado por correo serviría como credencial de acceso
a toda la API. Por eso:

- `JwtService.validarResetTokenYObtenerSujeto` **exige** `type = RESET_PASSWORD`,
  así que un Access JWT no sirve para cambiar una contraseña.
- `JwtTokenValidator` del gateway **rechaza** los tokens con ese claim, así que un
  Reset JWT no abre ningún recurso.

Hay un test para cada dirección.

La contraseña **nunca** entra en el token ni se almacena en claro: solo viaja en
el cuerpo de `/auth/login`, `/auth/reset-password` y `/auth/change-password`, y se
guarda como hash BCrypt con sal propia.

---

## Ciclo de vida de la cuenta

```
                        ┌──────────────────────┐
   empleado.creado ───► │ PENDIENTE_ACTIVACION │  (sin contraseña, rol USER)
                        └──────────┬───────────┘   └─► publica usuario.creado
                                   │
                  POST /auth/reset-password
                                   │  └─► publica cuenta.activada (ACTIVACION_INICIAL)
                                   ▼
         ┌────────────────────► ┌────────┐
         │                      │ ACTIVA │ ◄──── única con login permitido
         │                      └───┬────┘
         │   vacaciones.finalizadas │ vacaciones.iniciadas
         │   └─► cuenta.activada    │ └─► cuenta.desactivada (permanente=false)
         │      (FIN_VACACIONES)    ▼
         │              ┌─────────────────────┐
         └──────────────┤ SUSPENDIDA_TEMPORAL │
                        └──────────┬──────────┘
                                   │ empleado.retirado
                                   ▼
                     ┌────────────────────────┐
                     │ DESACTIVADA_PERMANENTE │  ◄── estado terminal
                     └────────────────────────┘    └─► cuenta.desactivada
                                                       (permanente=true)
```

### Eventos consumidos (`auth.queue`)

| Evento | Acción |
|---|---|
| `empleado.creado` | Crea la cuenta en `PENDIENTE_ACTIVACION`, rol `USER`, sin contraseña. Genera el token de activación y publica `usuario.creado` |
| `empleado.retirado` | `DESACTIVADA_PERMANENTE`. Publica `cuenta.desactivada` con `motivo: "RETIRO"`, `permanente: true` |
| `vacaciones.iniciadas` | `SUSPENDIDA_TEMPORAL`. Publica `cuenta.desactivada` con `motivo: "VACACIONES"`, `permanente: false` |
| `vacaciones.finalizadas` | Reactiva **solo si** estaba suspendida temporalmente. Publica `cuenta.activada` con `motivo: "FIN_VACACIONES"` |

### Eventos publicados (exchange `hr.events`)

| Evento | Disparador | `data` |
|---|---|---|
| `usuario.creado` | Consumo de `empleado.creado` | `id`, `email`, `token` |
| `usuario.recuperacion` | `POST /auth/recover-password` | `id`, `email`, `token` |
| `cuenta.activada` | Contraseña inicial, o `vacaciones.finalizadas` | `id`, `email`, `motivo` |
| `cuenta.desactivada` | `vacaciones.iniciadas` o `empleado.retirado` | `id`, `email`, `motivo`, `permanente` |

Todos usan el envelope de 6 campos del catálogo (`id`, `type`, `version`,
`occurredAt`, `producer`, `data`) con `producer: "auth-service"`.

> **Sobre el campo `id` del payload:** el catálogo especifica para estos cuatro
> eventos el `email`, el `token`, el `motivo` y el booleano `permanente`. El `id`
> se incluye siguiendo la convención ya establecida en el Reto 4, donde
> `EmployeeEventData.id` es el identificador de negocio y es el campo sobre el que
> el `notifications-service` indexa cada mensaje. No es un campo inventado: es el
> mismo contrato de `data.id` que ya cumplían los eventos de empleado.

---

## El caso borde del retiro durante vacaciones

Es el escenario que el enunciado evalúa de forma explícita.

Si un empleado se retira **mientras está de vacaciones**, al llegar la `fechaFin`
el scheduler publica `vacaciones.finalizadas` sobre una cuenta que ya tiene baja
permanente. La cuenta **no debe reactivarse**.

La garantía está en el modelo, no en una condición suelta:

```java
public boolean esReactivablePorVacaciones() {
    return this == SUSPENDIDA_TEMPORAL;
}
```

`DESACTIVADA_PERMANENTE` devuelve `false`, el manejador no toca la cuenta y el
login sigue fallando. Con un simple booleano `activa` esto sería imposible: el
manejador no podría saber si el `false` vino de unas vacaciones o de un retiro.
Ver [ADR-009](../../docs/decisions/ADR-009-estados-de-cuenta.md).

Lo cubre el test `retiroDuranteVacacionesImpideLaReactivacionAlFinalizarElPeriodo`.

---

## Configuración

Todo por variables de entorno; nada quemado en el código.

| Variable | Por defecto | Para qué |
|---|---|---|
| `SERVER_PORT` | `8085` | Puerto HTTP |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5436/gestion_auth` | Base de datos propia |
| `JWT_SECRET` | — | **Obligatorio.** Secreto HS256, mínimo 32 bytes |
| `JWT_ISSUER` | `system-hr-auth` | Claim `iss`, verificado al validar |
| `JWT_ACCESS_TTL_SECONDS` | `3600` | Vigencia del Access JWT |
| `JWT_RESET_TTL_SECONDS` | `3600` | Vigencia del token de activación/recuperación |
| `AUTH_SEED_ADMIN_ID` | `admin` | `empleadoId` del administrador semilla |
| `AUTH_SEED_ADMIN_EMAIL` | `admin@empresa.com` | Su email de login |
| `AUTH_SEED_ADMIN_PASSWORD` | Obligatoria en `.env`; sin valor predeterminado | Su contraseña inicial |
| `RABBITMQ_*` | `rabbitmq:5672`, `admin`/`admin` | Broker |

El servicio **aborta el arranque** si `JWT_SECRET` tiene menos de 32 bytes, en
lugar de firmar con una clave débil.

### Administrador semilla

Sin él no habría forma de empezar: los endpoints de escritura exigen rol `ADMIN`
y las cuentas de empleado nacen con rol `USER` desde `empleado.creado`. Se crea al
arrancar y es **idempotente**: si ya existe no se toca, así que un reinicio no
sobrescribe una contraseña que el administrador haya cambiado.

---

## Pruebas

```bash
./mvnw clean test
```

```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
```

En PowerShell o CMD el comando es `.\mvnw.cmd clean test`.

| Clase | Qué cubre |
|---|---|
| `AccountLifecycleTest` (7) | Alta, suspensión, reactivación, baja, **caso borde**, idempotencia y evento ajeno |
| `AuthEndpointsTest` (13) | Los cuatro endpoints, forma del JWT, hash BCrypt, política de contraseñas, separación entre los dos tipos de token, login bloqueado por vacaciones y administrador semilla |

Usan H2 en memoria y sustituyen el publisher por un mock, así que no necesitan
PostgreSQL ni RabbitMQ.

---

## Decisiones de diseño

**El `sub` es el `empleadoId`, no el email.** La comprobación de propiedad del
recurso compara `recurso.empleadoId == token.sub`, así que el `sub` tiene que ser
el identificador del empleado. El email queda como credencial de entrada, que
además puede cambiar sin romper los permisos ya concedidos.

**Cuatro estados en lugar de un booleano.** Detallado en
[ADR-009](../../docs/decisions/ADR-009-estados-de-cuenta.md). El cuarto estado,
`PENDIENTE_ACTIVACION`, evita que una cuenta nunca activada se vuelva `ACTIVA` al
terminar unas vacaciones que nunca tuvo.

**Token de reset stateless (Opción A).** No hay tabla de tokens ni limpieza de
expirados: la firma y el `exp` bastan para validarlo. El precio es que no se puede
revocar antes de que expire, mitigado con una vigencia corta.

**Solo `spring-security-crypto`, no el starter completo.** Se necesita
`BCryptPasswordEncoder`, no una cadena de filtros: la validación del JWT entrante
es del gateway. Añadir el starter obligaría a desactivar su seguridad por defecto
para nada.

**Un fallo al publicar no revierte la base de datos.** Si RabbitMQ no está
disponible, la cuenta queda en el estado correcto y el error se registra. Es la
misma política que fijó el Reto 4 para employees-service.

**Se marca como procesado incluso un evento sin efecto.** Si `vacaciones.finalizadas`
llega sobre una cuenta retirada, el evento se registra en `eventos_procesados`:
reprocesarlo tampoco tendría efecto, y así un reintento del broker no vuelve a
recorrer la lógica.

---

## Limitaciones conocidas

**Un Access JWT no se puede revocar.** Si un empleado es retirado mientras tiene
un token vigente, ese token sigue siendo válido hasta su `exp` (1 hora). El login
posterior falla, pero el token ya emitido no. Revocarlo exigiría una lista de
tokens inválidos o tokens de vida muy corta con refresh, que están fuera del
alcance del reto.

**Firma simétrica.** `auth-service` y `api-gateway` comparten el mismo secreto.
En producción se usaría RS256 y el gateway solo necesitaría la clave pública. El
enunciado admite HMAC por simplicidad académica.

**El secreto está en `.env.example`.** Es deliberado y el enunciado lo pide con
propósitos académicos. En un entorno real iría en un gestor de secretos.

**La recuperación revela si un email existe.** `POST /auth/recover-password`
devuelve `404` si no hay cuenta con ese correo. Lo correcto frente a enumeración
sería responder siempre `202`; se mantiene el `404` porque el flujo de prueba del
enunciado necesita distinguir el caso.
