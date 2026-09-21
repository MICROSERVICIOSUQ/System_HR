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
│ EmployeeRepository  │  Persistencia. Spring Data JPA sobre PostgreSQL.  
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
| `POST` | `/empleados` | Registrar un empleado asociado a un departamento | `201` | `400` · `503` |
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
  "status": 400,
  "error": "Bad Request",
  "message": "El email 'juan.perez@empresa.com' ya está registrado",
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
| `400` | `El id 'E001' ya está registrado` | Ya existe un empleado con ese `id` |
| `400` | `El email 'juan.perez@empresa.com' ya está registrado` | Ya existe un empleado con ese email |
| `400` | `El número de empleado 'EMP-2026-001' ya está registrado` | Ya existe un empleado con ese `numeroEmpleado` |
| `400` | `El email o número de empleado ya está registrado` | La restricción `UNIQUE` de PostgreSQL rechazó una carrera entre dos registros simultáneos |
| `404` | `El empleado con id E999 no existe` | `GET` con un `id` no registrado |
| `404` | `Recurso no encontrado` | Ruta no definida, o método HTTP no soportado en una ruta existente |

**Nota sobre el código de los duplicados.** Se evaluó devolver `409 Conflict` por
semántica HTTP (la petición está bien formada; el problema es el estado actual del
sistema), pero se usa `400 Bad Request` para alinear con la especificación del Reto 2.
El mensaje descriptivo es el mismo en ambos casos; solo cambia el código.

**Orden de comprobación al registrar:** `email` → `numeroEmpleado` → `id` →
`departamento`. Las tres primeras son consultas locales a la base de datos; la cuarta
es una llamada HTTP a `departments-service` con reintentos. Se agrupan las locales
antes de la remota para no gastar un round-trip (ni sus reintentos) en un registro que
fallará igualmente por duplicado. El orden visible para el cliente en las tres reglas
del contrato (`email`, `numeroEmpleado`, `departamento`) no cambia. Se detiene en la
primera que falla, así que para provocar el error de `numeroEmpleado` hay que enviar
un `email` libre, y para el de `departamento` hay que dejar libres los tres anteriores.

La validación de campos corre **antes** que todas ellas: un cuerpo al que le falten
campos devuelve `400` de validación aunque además duplique un `id`.

<details>
<summary><b>Ejemplos curl de cada caso de error</b></summary>

Partiendo de que `E001` (Juan) ya está registrado:

**`400` — Id duplicado** (email y numeroEmpleado libres)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E001","nombre":"Otro","apellido":"Empleado","email":"otro@empresa.com","numeroEmpleado":"EMP-2026-999","cargo":"Analista","area":"Ventas","departamentoId":"VT","fechaIngreso":"2026-04-01","estado":"ACTIVO"}'
```

**`400` — Email duplicado** (id libre)
```bash
curl -s -X POST http://localhost:8080/empleados -H "Content-Type: application/json" \
  -d '{"id":"E010","nombre":"Pedro","apellido":"López","email":"juan.perez@empresa.com","numeroEmpleado":"EMP-2026-888","cargo":"Analista","area":"Ventas","departamentoId":"VT","fechaIngreso":"2026-04-01","estado":"ACTIVO"}'
```

**`400` — numeroEmpleado duplicado** (id y email libres)
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
| 3 | Id duplicado → `400` con cuerpo JSON completo |
| 4 | Email duplicado → `400` |
| 5 | numeroEmpleado duplicado → `400` |
| 6 | Cuerpo `{}` → `400` enumerando los 10 campos obligatorios |
| 7 | Falta un campo → `400` señalando solo ese campo |
| 8 | Email con formato inválido → `400` |
| 9 | Estado fuera del enum → `400` |
| 10 | Id inexistente → `404` con `message` y `path` |
| 11 | Ruta no definida → `404 Recurso no encontrado` |
| 12 | Método no definido → `404 Recurso no encontrado` |

Usan **MockMvc**, por lo que no levantan Tomcat ni ocupan el puerto 8080: puedes
ejecutarlos con el servicio corriendo en paralelo. Van ordenados con `@Order` porque
comparten la base H2 en memoria del contexto de test: el primer test crea `E001` y el resto depende de él.

### Postman

Las colecciones viven en la carpeta [`postman/`](../../postman/) de la raíz del
repositorio, compartida por todos los servicios:

| Archivo | Cubre |
|---|---|
| `postman/empleados-service.postman_collection.json` | Este servicio: 201, duplicados 400, departamento inexistente 400, 404 exacto, listado |
| `postman/system-hr-ecosistema.postman_collection.json` | Flujo de integración con departments-service, encadenando variables |
| `postman/system-hr.postman_environment.json` | `empleadosBaseUrl` y `departamentosBaseUrl` |

Importa el environment y la colección, selecciona el environment **System HR - local**
y ejecuta con *Run collection*. Por línea de comandos:

```bash
npx newman run postman/empleados-service.postman_collection.json   -e postman/system-hr.postman_environment.json
```

La colección necesita que `departments-service` esté arriba (crea un departamento de
apoyo en el request 0) y que `E101` no exista aún: ejecútala sobre una base limpia.
Instrucciones completas en el [README global](../../README.md#pruebas-con-postman-y-newman).

---

## Decisiones de diseño

**El `id` lo asigna el cliente.** El servicio no genera UUIDs: recibe el `id` en el
cuerpo y lo respeta. Esto obliga a dos comprobaciones que no serían necesarias con ids
autogenerados:

- **Id obligatorio** (`@NotBlank`). Sin `id` no hay clave primaria con la que guardar.
- **Id único** (`400`). `save()` de JPA hace *upsert*: sin esta comprobación, un `POST`
  con un `id` ya existente reemplazaría al empleado anterior en silencio.

**La validación es declarativa y vive en el modelo.** Las anotaciones de Bean
La validación sobre `Employee` y el `@Valid` del controller rechazan la petición antes de
que llegue al service. El service queda con una sola responsabilidad: las reglas que
dependen del estado del repositorio (unicidad), que ninguna anotación puede expresar.

**Los errores se devuelven en JSON, no en texto plano.** Un cuerpo estructurado
(`timestamp`, `status`, `error`, `message`, `path`) permite que un cliente reaccione
por código en lugar de comparar cadenas. En los fallos de validación se añade
`errores`, con un objeto por campo, para que el formulario del cliente pueda marcar
cada uno en su sitio en vez de mostrar un mensaje único.

**Los duplicados son `400`, aunque `409` sería más preciso.** Un duplicado no es una
petición mal construida: está perfectamente formada y el problema es el estado actual
del sistema, que puede cambiar. Esa es la semántica de `409 Conflict`. Se evaluó
usarlo, pero se devuelve `400 Bad Request` para alinear con la especificación del
Reto 2; el mensaje descriptivo se conserva íntegro.

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
└── src/
    ├── main/
    │   ├── java/com/microservicios/employees/
    │   │   ├── EmployeesApplication.java
    │   │   ├── model/             # Employee, EmployeeStatus
    │   │   ├── repository/        # EmployeeRepository (Spring Data JPA)
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

### Fallo del servicio remoto (`departments-service`)

El microservicio se comunica vía HTTP con `departments-service` para verificar la existencia del `departamentoId` antes de registrar a un empleado. Para proteger el servicio ante problemas de red o caída de la dependencia:

1. **Timeouts explícitos:** `DepartmentClient` configura un tiempo de conexión de 3 s y de lectura de 5 s vía `SimpleClientHttpRequestFactory`. Si la dependencia no responde a tiempo, la llamada falla de inmediato liberando el hilo.
2. **Reintentos con retroceso exponencial:** Usando Spring Retry (`@Retryable`), las excepciones de conexión (`ResourceAccessException`) o errores 5xx del servidor remoto (`HttpServerErrorException`) disparan hasta **4 intentos** con espera creciente:
   $$\text{1s} \longrightarrow \text{2s} \longrightarrow \text{4s}$$
3. **Decisión al agotar reintentos (Fail-Fast):** Se rechaza la solicitud retornando `503 Service Unavailable` con el mensaje `"El servicio de departamentos no está disponible temporalmente"`.
   * **¿Por qué no se marca como pendiente?:** Aceptar al empleado como "pendiente de validación" comprometería la consistencia de los datos en PostgreSQL y violaría el modelo canónico donde el estado debe ser `ACTIVO`. No existe aún infraestructura de colas asíncronas para reconciliar el estado más adelante.

### Fallo de la base de datos PostgreSQL

Las operaciones de persistencia de `EmployeeService` también están protegidas ante fallos transitorios de la base de datos propia del servicio:

1. **Reintentos con retroceso exponencial:** Cada método público del service (`register`, `findById`, `findAll`) está anotado con `@Retryable`. Ante errores de acceso a datos, ejecuta hasta **3 intentos** con espera creciente:
   $$\text{250 ms} \longrightarrow \text{500 ms}$$

2. **Excepciones cubiertas:** Se capturan todas las excepciones transitorias que JPA/Hibernate/HikariCP pueden lanzar cuando PostgreSQL no está disponible en runtime:

   | Excepción | Cuándo ocurre |
   |---|---|
   | `DataAccessResourceFailureException` | Fallo de acceso al pool de conexiones |
   | `CannotCreateTransactionException` | No se puede iniciar una transacción |
   | `QueryTimeoutException` | La consulta supera el tiempo configurado |
   | `JpaSystemException` | JPA envuelve una `PSQLException` de conexión rota en runtime |

3. **Respuesta al agotar reintentos:** El `GlobalExceptionHandler` captura cualquiera de esas cuatro excepciones y responde `503 Service Unavailable` con el mensaje `"La base de datos no está disponible temporalmente"`. El proceso del servicio no se interrumpe.

4. **Logging de reintentos:** El `RetryLoggingListener` registra cada intento fallido y el agotamiento final en el log, facilitando el diagnóstico:

   ```
   WARN  Intento 1 fallido en EmployeeService.findAll: JpaSystemException — ...
   WARN  Intento 2 fallido en EmployeeService.findAll: JpaSystemException — ...
   ERROR Reintentos agotados en EmployeeService.findAll tras 3 intentos
   ```

---

## Limitaciones conocidas

**Persistencia en base de datos dedicada.** Los datos se almacenan en PostgreSQL (`gestion_empleados`), aislada del resto de servicios.

**Operaciones implementadas.** Se admiten registro (`POST /empleados`), consulta individual (`GET /empleados/{id}`) y listado total (`GET /empleados`). No existen endpoints de actualización ni de baja física en esta versión.

**Estado fijo.** El `estado` se normaliza siempre a `ACTIVO` en el registro. Las transiciones a `EN_VACACIONES` y `RETIRADO` se implementan en retos posteriores.