# gestion-empleados

Microservicio REST para el registro y consulta de empleados. Java 21, Spring Boot 3.4,
PostgreSQL mediante Spring Data JPA y manejo centralizado de errores.

Las capas están separadas de forma que sustituir el almacenamiento o cambiar el
transporte no obligue a reescribir la lógica de negocio.

---

## Tabla de contenido

- [Arquitectura](#arquitectura)
- [Modelo de datos](#modelo-de-datos)
- [API](#api)
  - [GET /empleados/health](#get-empleadoshealth)
  - [Resumen](#resumen)
  - [POST /empleados](#post-empleados)
  - [GET /empleados/{id}](#get-empleadosid)
  - [Catálogo de errores](#catálogo-de-errores)
- [Puesta en marcha](#puesta-en-marcha)
- [Pruebas](#pruebas)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Solución de problemas](#solución-de-problemas)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Limitaciones conocidas](#limitaciones-conocidas)

---

## Arquitectura

Tres capas con dependencias en una sola dirección, más un manejador de errores
transversal:

```
   HTTP
     │
     ▼
┌─────────────────────┐
│ EmployeeController  │  Traduce HTTP ↔ dominio. Sin lógica de negocio.
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ EmployeeService     │  Reglas e invariantes: unicidad, id obligatorio, estado.
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ EmployeeRepository  │  Persistencia. Hoy ConcurrentHashMap, mañana una BD.
└─────────────────────┘

┌──────────────────────────┐
│ GlobalExceptionHandler   │  @RestControllerAdvice — mapea excepciones a HTTP.
└──────────────────────────┘
```

| Capa | Responsabilidad | Por qué está separada |
|---|---|---|
| `controller` | Recibir la petición y devolver la respuesta | Cambiar el transporte (REST → mensajería) no toca las reglas |
| `service` | Validaciones y reglas de negocio | Es el único lugar donde vive la lógica; se prueba sin HTTP |
| `repository` | Guardar y recuperar | Sustituir el mapa en memoria por JPA no obliga a tocar el controller |
| `exception` | Excepciones de dominio + traducción a HTTP | Los códigos y mensajes se definen en un solo sitio, no ruta por ruta |

---

## Modelo de datos

**Todos los campos son obligatorios.** La validación es declarativa (`@NotBlank`,
`@NotNull`, `@Email`) y se dispara con `@Valid` en el controller, antes de que la
petición llegue a la lógica de negocio.

| Campo | Tipo | Ejemplo | Validación | Único |
|---|---|---|---|---|
| `id` | String | `E001` | `@NotBlank` | Sí |
| `nombre` | String | `Juan` | `@NotBlank` | — |
| `apellido` | String | `Pérez` | `@NotBlank` | — |
| `email` | String | `juan.perez@empresa.com` | `@NotBlank` `@Email` | Sí, ignorando mayúsculas |
| `numeroEmpleado` | String | `EMP-2026-001` | `@NotBlank` | Sí, distinguiendo mayúsculas |
| `cargo` | String | `Desarrollador Senior` | `@NotBlank` | — |
| `area` | String | `Tecnología` | `@NotBlank` | — |
| `departamentoId` | String | `IT` | `@NotBlank` | — |
| `fechaIngreso` | LocalDate | `2026-02-10` | `@NotNull`, formato `yyyy-MM-dd` | — |
| `estado` | Enum | `ACTIVO` | `@NotNull`, uno de `ACTIVO` · `EN_VACACIONES` · `RETIRADO` | — |

`@NotBlank` rechaza tanto el campo ausente como la cadena vacía o compuesta solo de
espacios. `departamentoId` es texto libre: se exige que venga, pero no se valida
contra ningún otro servicio.

**Sobre el `id`:** no se autogenera. Lo envías en el `POST` y con ese mismo valor
consultas en el `GET`. El formato es libre (`E001`, `EMP-1`, `juan-perez`); solo se
valida que no venga vacío y que no exista ya.

**Sobre el `estado`:** es obligatorio enviarlo, pero el registro lo normaliza siempre a
`ACTIVO`; si envías `EN_VACACIONES` o `RETIRADO` se guardará como `ACTIVO`. No hay
operaciones de transición de estado.

---

## API

### GET /empleados/health

```bash
curl -s http://localhost:8080/empleados/health
```

**Respuesta esperada (200 OK):**

```json
{
  "status": "ok",
  "service": "empleados"
}
```

Base URL por defecto: `http://localhost:8080`

### Documentación Interactiva (Swagger UI)
* **Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
* **Especificación OpenAPI 3.0 (JSON):** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### Resumen

| Método | Ruta | Descripción | Éxito | Errores |
|---|---|---|---|---|
| `GET` | `/empleados/health` | Verificar disponibilidad del servicio | `200` | — |
| `POST` | `/empleados` | Registrar un empleado asociado a un departamento | `201` | `400` · `409` · `503` |
| `GET` | `/empleados` | Listar todos los empleados registrados | `200` | `503` |
| `GET` | `/empleados/{id}` | Consultar empleado por identificador | `200` | `404` · `503` |
| *(cualquiera)* | *(cualquier otra ruta)* | — | — | `404` |

Éxitos y errores se devuelven en `application/json`.

### POST /empleados

```bash
curl -s -X POST http://localhost:8080/empleados \
  -H "Content-Type: application/json" \
  -d '{
    "id": "E001",
    "nombre": "Juan",
    "apellido": "Pérez",
    "email": "juan.perez@empresa.com",
    "numeroEmpleado": "EMP-2026-001",
    "cargo": "Desarrollador Senior",
    "area": "Tecnología",
    "departamentoId": "IT",
    "fechaIngreso": "2026-02-10",
    "estado": "ACTIVO"
  }'
```

**`200 OK`** — devuelve el empleado guardado, con el `id` que enviaste:

```json
{
  "id": "E001",
  "nombre": "Juan",
  "apellido": "Pérez",
  "email": "juan.perez@empresa.com",
  "numeroEmpleado": "EMP-2026-001",
  "cargo": "Desarrollador Senior",
  "area": "Tecnología",
  "departamentoId": "IT",
  "fechaIngreso": "2026-02-10",
  "estado": "ACTIVO"
}
```

### GET /empleados/{id}

```bash
curl -s http://localhost:8080/empleados/E001
```

**`200 OK`** — el JSON completo del empleado.
**`404 Not Found`** — cuerpo de error con `message: "El empleado con id E001 no existe"`.

### Catálogo de errores

Todos los errores comparten la misma forma:

```json
{
  "timestamp": "2026-09-08T18:42:11.503-05:00",
  "status": 409,
  "error": "Conflict",
  "message": "El id 'E001' ya está registrado",
  "path": "/empleados"
}
```

El campo `errores` se añade únicamente en los fallos de validación, con el detalle
por campo:

```json
{
  "timestamp": "2026-09-08T18:42:11.503-05:00",
  "status": 400,
  "error": "Bad Request",
  "message": "La solicitud contiene campos inválidos",
  "path": "/empleados",
  "errores": [
    { "campo": "email",  "mensaje": "El email es obligatorio" },
    { "campo": "nombre", "mensaje": "El nombre es obligatorio" }
  ]
}
```

| HTTP | `message` | Se produce cuando |
|---|---|---|
| `400` | `La solicitud contiene campos inválidos` | Falta un campo obligatorio o el email no tiene formato válido. Incluye `errores` |
| `400` | `El cuerpo de la petición no es un JSON válido o contiene valores con formato incorrecto` | JSON con sintaxis rota, `estado` fuera del enum, o `fechaIngreso` mal formada |
| `409` | `El id 'E001' ya está registrado` | Ya existe un empleado con ese `id` |
| `409` | `El email 'juan.perez@empresa.com' ya está registrado` | Ya existe un empleado con ese email |
| `409` | `El número de empleado 'EMP-2026-001' ya está registrado` | Ya existe un empleado con ese `numeroEmpleado` |
| `404` | `El empleado con id E999 no existe` | `GET` con un `id` no registrado |
| `404` | `Recurso no encontrado` | Ruta no definida, o método HTTP no soportado en una ruta existente |

**400 frente a 409.** El `400` dice que la petición está mal construida: le faltan
datos o tienen mal formato, y reenviarla igual siempre fallará. El `409` dice que la
petición es correcta pero choca con información que ya está en el sistema; la misma
petición podría funcionar en otro momento o con otro identificador.

**Orden de comprobación de conflictos:** `id` → `email` → `numeroEmpleado`. Se detiene
en el primero, así que para provocar el conflicto de `email` hay que enviar un `id`
libre, y para el de `numeroEmpleado` hay que dejar libres los dos anteriores.

La validación de campos corre **antes** que las comprobaciones de unicidad: un cuerpo
al que le falten campos devuelve `400` aunque además duplique un `id`.

<details>
<summary><b>Ejemplos curl de cada caso de error</b></summary>

Partiendo de que `E001` (Juan) ya está registrado:

**`409` — Id duplicado** (email y numeroEmpleado libres)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E001","nombre":"Otro","apellido":"Empleado","email":"otro@empresa.com","numeroEmpleado":"EMP-2026-999","cargo":"Analista","area":"Ventas","departamentoId":"VT","fechaIngreso":"2026-04-01","estado":"ACTIVO"}'
```

**`409` — Email duplicado** (id libre)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E010","nombre":"Pedro","apellido":"López","email":"juan.perez@empresa.com","numeroEmpleado":"EMP-2026-888","cargo":"Analista","area":"Ventas","departamentoId":"VT","fechaIngreso":"2026-04-01","estado":"ACTIVO"}'
```

**`409` — numeroEmpleado duplicado** (id y email libres)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E011","nombre":"Pedro","apellido":"López","email":"pedro.lopez@empresa.com","numeroEmpleado":"EMP-2026-001","cargo":"Analista","area":"Ventas","departamentoId":"VT","fechaIngreso":"2026-04-01","estado":"ACTIVO"}'
```

**`400` — Faltan todos los campos** (devuelve los 10 en `errores`)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" -d '{}'
```

**`400` — Falta un campo concreto** (aquí `nombre`)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E030","apellido":"Pérez","email":"sin.nombre@empresa.com","numeroEmpleado":"EMP-2026-030","cargo":"Analista","area":"Tecnología","departamentoId":"IT","fechaIngreso":"2026-02-10","estado":"ACTIVO"}'
```

**`400` — Email con formato inválido**
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E031","nombre":"Correo","apellido":"Malo","email":"esto-no-es-un-correo","numeroEmpleado":"EMP-2026-031","cargo":"Analista","area":"Tecnología","departamentoId":"IT","fechaIngreso":"2026-02-10","estado":"ACTIVO"}'
```

**`400` — Estado fuera del enum**
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E032","nombre":"Estado","apellido":"Invalido","email":"estado.malo@empresa.com","numeroEmpleado":"EMP-2026-032","cargo":"Analista","area":"Tecnología","departamentoId":"IT","fechaIngreso":"2026-02-10","estado":"JUBILADO"}'
```

**`404` — Empleado inexistente**
```bash
curl -s http://localhost:8080/empleados/E999
```

**`404` — Ruta no definida**
```bash
curl -s http://localhost:8080/ruta-invalida
```

**`404` — Método no definido**
```bash
curl -s http://localhost:8080/empleados
```

</details>

> **Windows:** los ejemplos usan sintaxis de shell POSIX (comillas simples, `\` al
> final de línea). Ejecútalos en **Git Bash**. En PowerShell las comillas simples no
> protegen el JSON: guarda el cuerpo en un archivo y usa `-d "@empleado.json"`.

---

## Puesta en marcha

### Requisitos

| | Versión | Necesario para |
|---|---|---|
| **Java** | 21 | Compilar y ejecutar |
| **Docker** | cualquiera reciente | Ejecución en contenedor |
| ~~Maven~~ | — | No hace falta: el wrapper (`mvnw`) va incluido |

> Los comandos usan `./mvnw` (Linux, macOS, Git Bash). En **PowerShell** o **CMD**
> el equivalente es `.\mvnw.cmd` — por ejemplo `.\mvnw.cmd clean test`.

### Opción A — Maven Wrapper (desarrollo)

```bash
./mvnw spring-boot:run
```

### Opción B — JAR ejecutable

```bash
./mvnw clean package -DskipTests
java -jar target/employees-service-0.0.1-SNAPSHOT.jar
```

Para usar otro puerto:

```bash
java -jar target/employees-service-0.0.1-SNAPSHOT.jar --server.port=9090
```

### Opción C — Docker

```bash
docker build -t servidor-empleados .
```

```bash
docker run -p 8080:8080 servidor-empleados
```

La imagen usa un build **multi-stage**: compila con `maven:3.9-eclipse-temurin-21` y
entrega solo el JAR sobre `eclipse-temurin:21-jre-alpine` (~325 MB, sin Maven ni
código fuente).

El flag `-p` es obligatorio: sin él el contenedor arranca correctamente pero su
puerto no se publica y el servicio queda inalcanzable desde el host. Si el 8080 está
ocupado, cambia solo el lado del host: `-p 9090:8080`.

---

## Pruebas

### Automatizadas

```bash
./mvnw clean test
```

```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los 12 tests de [`EmployeeEndpointsTest`](src/test/java/com/microservicios/employees/EmployeeEndpointsTest.java)
cubren el contrato completo:

| # | Verifica |
|---|---|
| 1 | El `POST` conserva el `id` enviado por el cliente |
| 2 | El `GET` recupera el empleado por ese `id` |
| 3 | Id duplicado → `409` con cuerpo JSON completo |
| 4 | Email duplicado → `409` |
| 5 | numeroEmpleado duplicado → `409` |
| 6 | Cuerpo `{}` → `400` enumerando los 10 campos obligatorios |
| 7 | Falta un campo → `400` señalando solo ese campo |
| 8 | Email con formato inválido → `400` |
| 9 | Estado fuera del enum → `400` |
| 10 | Id inexistente → `404` con `message` y `path` |
| 11 | Ruta no definida → `404 Recurso no encontrado` |
| 12 | Método no definido → `404 Recurso no encontrado` |

Usan **MockMvc**, por lo que no levantan Tomcat ni ocupan el puerto 8080: puedes
ejecutarlos con el servicio corriendo en paralelo. Van ordenados con `@Order` porque
comparten el estado en memoria: el primer test crea `E001` y el resto depende de él.

### Postman

La colección [`etc/gestion-empleados.postman_collection.json`](etc/gestion-empleados.postman_collection.json)
trae los 10 requests organizados en tres carpetas, cada uno con aserciones
automáticas y respuesta de ejemplo.

1. **Import** → arrastra el archivo.
2. Ejecuta primero `1.1 Registrar empleado (E001 - Juan)`.
3. El resto usa `E001` directamente, sin variables intermedias.

Si el servicio no corre en `http://localhost:8080`, ajusta la variable `baseUrl` de
la colección — pestaña *Variables*, columna **Current value**.

---

## Decisiones de diseño

**El `id` lo asigna el cliente.** El servicio no genera UUIDs: recibe el `id` en el
cuerpo y lo respeta. Esto obliga a dos comprobaciones que no serían necesarias con ids
autogenerados:

- **Id obligatorio** (`@NotBlank`). Sin `id` no hay clave con la que guardar; además
  `ConcurrentHashMap` no admite claves nulas y lanzaría un `500`.
- **Id único** (`409`). `Map.put()` sobrescribe en silencio. Sin esta comprobación, un
  `POST` con un `id` ya existente reemplazaría al empleado anterior y devolvería `200`
  como si nada hubiera pasado.

**La validación es declarativa y vive en el modelo.** Las anotaciones de Bean
La validación sobre `Employee` y el `@Valid` del controller rechazan la petición antes de
que llegue al service. El service queda con una sola responsabilidad: las reglas que
dependen del estado del repositorio (unicidad), que ninguna anotación puede expresar.

**Los errores se devuelven en JSON, no en texto plano.** Un cuerpo estructurado
(`timestamp`, `status`, `error`, `message`, `path`) permite que un cliente reaccione
por código en lugar de comparar cadenas. En los fallos de validación se añade
`errores`, con un objeto por campo, para que el formulario del cliente pueda marcar
cada uno en su sitio en vez de mostrar un mensaje único.

**Los duplicados son `409`, no `400`.** Un `400` significa que la petición está mal
construida y reenviarla igual siempre fallará. Un duplicado es distinto: la petición
está perfectamente formada y el problema es el estado actual del sistema, que puede
cambiar. Esa es exactamente la semántica de `409 Conflict`.

**Los 404 se resuelven de forma global.** `spring.mvc.throw-exception-if-no-handler-found=true`
y `spring.web.resources.add-mappings=false` hacen que Spring lance
`NoHandlerFoundException` en lugar de servir su página de error; el
`@RestControllerAdvice` la traduce. No hay comprobaciones ruta por ruta.

**Un método no soportado devuelve 404, no 405.** Spring responde `405` por defecto. El
manejador captura también `HttpRequestMethodNotSupportedException` para unificar en
una sola respuesta todo lo que el servicio no resuelve, sin revelar al cliente qué
rutas existen con otros métodos.

**La persistencia pertenece al servicio.** `EmployeeRepository` usa Spring Data JPA
contra la base PostgreSQL `gestion_empleados`. La tabla `employees` es exclusiva de
este microservicio y no comparte datos ni tablas con `departments-service`.

Las migraciones se gestionan con Flyway dentro de `src/main/resources/db/migration`.
El servicio aplica las migraciones pendientes al iniciar y usa un baseline compatible
con bases creadas previamente durante la etapa inicial del proyecto.

**Sin Lombok ni dependencias extra.** Solo `spring-boot-starter-web` en tiempo de
ejecución. Los getters y setters son explícitos, sin procesamiento de anotaciones en
compilación: menos superficie de dependencias y un JAR más pequeño.

---

## Solución de problemas

| Síntoma | Causa | Solución |
|---|---|---|
| `Port 8080 was already in use` | Otro proceso ocupa el puerto (Apache, otra instancia) | `--server.port=9090`, o liberar el 8080 |
| `docker: port is already allocated` | Igual, desde Docker | `docker run -p 9090:8080 servidor-empleados` |
| El contenedor arranca pero no responde | Falta el `-p`: el puerto no se publica al host | Añadir `-p 8080:8080` |
| `ClassNotFoundException` en los tests | `target/` en estado inconsistente | Usar `clean`: `./mvnw clean test` |
| `mvn: command not found` | Maven no está instalado | Usar el wrapper: `./mvnw` |
| Aviso de *Mockito self-attaching* | Ruido normal de Spring Boot 3.4 + JDK 21 | Ignorar; no es un fallo |

---

## Estructura del proyecto

```
employees-service/
├── Dockerfile                     # Build multi-stage
├── .dockerignore
├── pom.xml
├── mvnw / mvnw.cmd                # Maven Wrapper (no requiere Maven instalado)
├── .mvn/wrapper/
├── etc/
│   └── gestion-empleados.postman_collection.json
└── src/
    ├── main/
    │   ├── java/com/microservicios/employees/
    │   │   ├── EmployeesApplication.java
    │   │   ├── model/             # Employee, EmployeeStatus
    │   │   ├── repository/        # EmployeeRepository (ConcurrentHashMap)
    │   │   ├── service/           # EmployeeService
    │   │   ├── controller/        # EmployeeController
    │   │   └── exception/         # Excepciones de dominio + GlobalExceptionHandler
    │   └── resources/
    │       └── application.properties
    └── test/java/com/microservicios/employees/
      └── EmployeeEndpointsTest.java
```

---

## Resiliencia ante fallos de dependencias

El microservicio se comunica vía HTTP con `departments-service` para verificar la existencia del `departamentoId` antes de registrar a un empleado. Para proteger el servicio ante problemas de red o caída de la dependencia:

1. **Timeouts explícitos:** `DepartmentClient` configura un tiempo de conexión de 3s y de lectura de 5s vía `SimpleClientHttpRequestFactory`. Si la dependencia no responde a tiempo, la llamada falla de inmediato liberando el hilo.
2. **Reintentos con retroceso exponencial:** Utilizando Spring Retry (`@Retryable`), las excepciones de conexión (`ResourceAccessException`) o errores 5xx del servidor remoto (`HttpServerErrorException`) disparan hasta 3 intentos con una espera creciente:
   $$\text{1s} \longrightarrow \text{2s} \longrightarrow \text{4s}$$
3. **Decisión al agotar reintentos (Fail-Fast):**
   * Se rechaza la solicitud retornando `503 Service Unavailable` con el mensaje:
     `"El servicio de departamentos no está disponible temporalmente"`.
   * **¿Por qué no se marca como pendiente?:** Aceptar al empleado como "pendiente de validación" comprometería la consistencia de los datos en PostgreSQL (creando posibles registros huérfanos con departamentos inválidos) y violaría el modelo canónico donde el estado debe ser `ACTIVO`. Además, no existe aún una infraestructura de colas asíncronas para reconciliar el estado más adelante.

---

## Limitaciones conocidas

**Persistencia en base de datos dedicada.** Los datos se almacenan en PostgreSQL (`gestion_empleados`), aislada del resto de servicios.

**Operaciones implementadas.** Se admiten registro (`POST /empleados`), consulta individual (`GET /empleados/{id}`) y listado total (`GET /empleados`). No existen endpoints de actualización ni de baja física en esta versión.

**Estado fijo.** El `estado` se normaliza siempre a `ACTIVO` en el registro. Las transiciones a `EN_VACACIONES` y `RETIRADO` se implementan en retos posteriores.