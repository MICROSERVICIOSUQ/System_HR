# Sistema de Recursos Humanos

Plataforma de microservicios para la gestion de recursos humanos.

## Estructura

```text
sistema_rrhh/
|-- services/              Microservicios independientes
|-- api-gateway/           Punto de entrada comun
|-- infrastructure/       Bases de datos, scripts y configuracion auxiliar
|-- tests/                 Pruebas que involucran varios servicios
|-- docs/                  Arquitectura, diagramas y decisiones tecnicas
|-- docker-compose.yml     Orquestacion del entorno completo
|-- .env.example           Variables de entorno de referencia
```

## Microservicios

| Servicio | Tecnologia | Puerto local | Persistencia | Documentación Swagger UI |
|---|---|---:|---|---|
| departments-service | Node.js, TypeScript, Prisma | 8081 | PostgreSQL propia | [Swagger UI (localhost:8081/docs)](http://localhost:8081/docs) |
| employees-service | Java 21, Spring Boot | 8080 | PostgreSQL propia | [Swagger UI (localhost:8080/swagger-ui.html)](http://localhost:8080/swagger-ui.html) |

Cada microservicio mantiene su propio codigo, reglas de negocio, contrato HTTP y esquema de persistencia. Las bases de datos no se comparten entre servicios.

## Documentación con OpenAPI (Swagger)

En cumplimiento con el principio de **autonomía de los microservicios**, cada servicio es autodescriptivo (*self-describing*) y expone de forma desacoplada su contrato OpenAPI 3.0 y su interfaz gráfica de Swagger UI. Esto garantiza que cualquier servicio pueda ser probado, consumido o integrado de manera independiente en otros ecosistemas:

| Microservicio | Interfaz Gráfica (Swagger UI) | Especificación JSON (OpenAPI 3.0) | Librería utilizada |
|---|---|---|---|
| **`departments-service`** | [http://localhost:8081/docs](http://localhost:8081/docs) | [http://localhost:8081/docs/openapi.json](http://localhost:8081/docs/openapi.json) | `swagger-ui-express` |
| **`employees-service`** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs) | `springdoc-openapi-starter-webmvc-ui` |

Cada interfaz permite probar interactivamente los endpoints y documenta:
- Propósito detallado de cada operación y parámetros requeridos.
- Códigos de respuesta HTTP posibles (`200 OK`, `201 Created`, `204 No Content`, `400 Bad Request`, `404 Not Found`, `503 Service Unavailable`).
- Esquemas de datos (*Schemas*) para las cargas útiles de entrada (*request bodies*) y modelos de dominio de salida con ejemplos.

## Despliegue y Puesta en Marcha desde Cero

Para que cualquier desarrollador o evaluador clone el repositorio y levante el sistema completo sin requerir pasos manuales adicionales:

### 1. Clonar el repositorio y preparar variables de entorno

```bash
git clone <url-del-repositorio>
cd sistema_rrhh

# Crear el archivo de entorno local a partir del ejemplo
cp .env.example .env     # En Linux / macOS / Git Bash
copy .env.example .env   # En Windows PowerShell / CMD
```

### 2. Iniciar el entorno completo

```bash
docker compose up --build
```

Al ejecutar este comando:
1. Docker descarga y levanta las bases de datos PostgreSQL independientes (`database-departamentos` y `database-empleados`).
2. Se evalúan los health checks de PostgreSQL con `pg_isready` hasta que ambas bases estén listas.
3. Se compilan y construyen las imágenes de los microservicios.
4. Los esquemas y tablas de ambas bases de datos se crean automáticamente durante el arranque de cada servicio (mediante migraciones versionadas).
5. Las APIs quedan publicadas en los puertos del host: `8081` (Departamentos) y `8080` (Empleados).

### 3. Verificar el estado del sistema

```bash
docker compose ps
```

Comprobar la disponibilidad con los endpoints de salud:

```bash
# Salud del servicio de Departamentos
curl -s http://localhost:8081/health

# Salud del servicio de Empleados
curl -s http://localhost:8080/empleados/health
```

### 4. Detener o reiniciar el entorno

* **Detener servicios conservando datos:**
  ```bash
  docker compose down
  ```
* **Reiniciar desde cero eliminando volúmenes de base de datos:**
  ```bash
  docker compose down -v
  docker compose up --build
  ```

---

## Evidencias

Salida real de terminal, capturada el 2026-09-15 sobre este repositorio. En la
máquina donde se capturó, el puerto 8080 estaba ocupado por otro proceso, así que
`employees-service` se publicó con `EMPLEADOS_PORT=8080` (equivale a fijarlo en
`.env`); nada más cambia. El volumen `softwareiii_postgres_data` que aparece en los
listados pertenece a otro proyecto de la misma máquina y es ajeno a este sistema.

### Persistencia: `down` conserva los datos, `down -v` los destruye

**1. Arranque limpio y siembra de datos**

```console
$ docker compose down -v
 Volume gestion-departamentos_postgres_data Removed
 Volume gestion-empleados_postgres_data Removed
 Network sistema_rrhh_network Removed

$ docker compose up --build -d
Container system_hr-database-empleados-1 Started
Container system_hr-database-departamentos-1 Started
Container system_hr-database-departamentos-1 Healthy
Container system_hr-departments-service-1 Started
Container system_hr-database-empleados-1 Healthy
Container system_hr-departments-service-1 Healthy
Container system_hr-employees-service-1 Started

$ curl -X POST /departamentos  {"id":"IT",...}
{"id":"IT","nombre":"Tecnología","descripcion":"Departamento de TI","createdAt":"2026-09-16T02:17:14.009Z","updatedAt":"2026-09-16T02:17:14.009Z"}   -> HTTP 201

$ curl -X POST /empleados  {"id":"E001",...,"departamentoId":"IT"}
{"id":"E001","nombre":"Juan","apellido":"Pérez","email":"juan.perez@empresa.com","cargo":"Desarrollador Senior","area":"Tecnología","departamentoId":"IT","fechaIngreso":"2026-02-10","estado":"ACTIVO","numeroEmpleado":"EMP-2026-001"}   -> HTTP 201
```

**2. `down` sin `-v` → `up -d` → el empleado sobrevive**

```console
$ docker compose down
Container system_hr-employees-service-1 Removed
Container system_hr-departments-service-1 Removed
Container system_hr-database-empleados-1 Removed
Container system_hr-database-departamentos-1 Removed
Network sistema_rrhh_network Removed

$ docker volume ls | grep postgres_data
gestion-departamentos_postgres_data
gestion-empleados_postgres_data
softwareiii_postgres_data

$ docker compose up -d
Container system_hr-database-empleados-1 Started
Container system_hr-database-departamentos-1 Started
Container system_hr-database-departamentos-1 Healthy
Container system_hr-departments-service-1 Started
Container system_hr-database-empleados-1 Healthy
Container system_hr-departments-service-1 Healthy
Container system_hr-employees-service-1 Started

$ curl /empleados/E001
{"id":"E001","nombre":"Juan","apellido":"Pérez","email":"juan.perez@empresa.com","cargo":"Desarrollador Senior","area":"Tecnología","departamentoId":"IT","fechaIngreso":"2026-02-10","estado":"ACTIVO","numeroEmpleado":"EMP-2026-001"}   -> HTTP 200
```

Los contenedores se destruyeron y se recrearon; los volúmenes no se tocaron y
`E001` sigue ahí.

**3. `down -v` → `up -d --build` → el empleado desaparece**

```console
$ docker compose down -v
Container system_hr-employees-service-1 Removed
Container system_hr-database-empleados-1 Removed
Container system_hr-departments-service-1 Removed
Container system_hr-database-departamentos-1 Removed
Volume gestion-empleados_postgres_data Removed
Volume gestion-departamentos_postgres_data Removed
Network sistema_rrhh_network Removed

$ docker volume ls | grep postgres_data
softwareiii_postgres_data

$ docker compose up -d --build
Container system_hr-database-departamentos-1 Started
Container system_hr-database-empleados-1 Started
Container system_hr-database-departamentos-1 Healthy
Container system_hr-departments-service-1 Started
Container system_hr-database-empleados-1 Healthy
Container system_hr-departments-service-1 Healthy
Container system_hr-employees-service-1 Started

$ curl /empleados/E001
{"timestamp":"2026-09-16T02:18:29.034525442Z","status":404,"error":"Not Found","message":"El empleado con id E001 no existe","path":"/empleados/E001"}   -> HTTP 404
```

Con `-v` los dos volúmenes se eliminaron; las migraciones recrearon el esquema
vacío y `E001` ya no existe.

### Arranque ordenado: los cuatro servicios `healthy`

Tras `docker compose down -v && docker compose up --build`:

```console
$ docker compose ps
NAME                                 STATUS                    PORTS
system_hr-database-departamentos-1   Up 25 seconds (healthy)   5432/tcp
system_hr-database-empleados-1       Up 25 seconds (healthy)   5432/tcp
system_hr-departments-service-1      Up 19 seconds (healthy)   0.0.0.0:8081->8081/tcp, [::]:8081->8081/tcp
system_hr-employees-service-1        Up 13 seconds (healthy)   0.0.0.0:8090->8090/tcp, [::]:8090->8090/tcp
```

El orden de las marcas `Healthy` en la salida de `up` muestra la cadena de
dependencias: las bases primero, `departments-service` cuando su base está
`healthy`, y `employees-service` en último lugar, cuando tanto su base como
`departments-service` lo están. Las migraciones se aplican durante ese arranque:

```console
$ docker compose logs employees-service | grep -i "flyway\|Started"
2026-09-16T02:18:19.874Z  INFO 1 --- [main] o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.042s)
2026-09-16T02:18:22.484Z  INFO 1 --- [main] c.m.employees.EmployeesApplication  : Started EmployeesApplication in 6.178 seconds (process running for 6.705)

$ docker compose logs departments-service | grep -i "migration\|Server running"
3 migrations found in prisma/migrations
The following migration(s) have been applied:
Server running on http://localhost:8081
```

---

## Decisiones técnicas

Las tres decisiones de persistencia están registradas como ADR en
[`docs/decisions/`](docs/decisions/README.md). Aquí va el resumen; el contexto
completo, las alternativas descartadas y las consecuencias están en cada ADR.

### (a) Motor de base de datos: un solo motor, instancias separadas — [ADR-001](docs/decisions/ADR-001-motor-de-base-de-datos.md)

Ambos servicios usan **PostgreSQL 16**, cada uno en su propio contenedor, con su
propio volumen y sus propias credenciales. La separación es a nivel de instancia,
no solo de esquema: `database-departamentos` y `database-empleados` no comparten
proceso, puerto ni volumen.

Se evaluó la **persistencia políglota** —elegir el motor óptimo por caso de uso,
por ejemplo un almacén de documentos para departamentos, que es un catálogo plano
de lectura frecuente— y se descartó. La ventaja para tres campos planos es
marginal; el costo es concreto: dos motores que aprender, dos herramientas de
migración con semánticas distintas, dos estrategias de backup y el doble de
superficie para fallos de arranque, en un equipo de dos personas con experiencia
previa en SQL y sin infraestructura de operación.

Lo que se gana: una sola herramienta de backup, monitoreo y diagnóstico; el mismo
healthcheck (`pg_isready`) para ambas bases; el mismo comportamiento de
restricciones y transacciones en los dos servicios. Lo que se sacrifica: la opción
de optimizar el motor por servicio. Si departamentos creciera hacia documentos de
estructura variable, PostgreSQL seguiría sirviendo (`jsonb`), pero no sería la
elección ideal. Como cada servicio es dueño exclusivo de su base, cualquiera de los
dos puede migrar a otro motor en el futuro sin tocar al otro.

### (b) Estrategia de creación de esquema: migraciones versionadas — [ADR-002](docs/decisions/ADR-002-estrategia-de-esquema.md)

Flyway en `employees-service` y Prisma Migrate en `departments-service`, aplicadas
automáticamente al arrancar cada contenedor. Detalle y comparación con `init.sql`
y auto-DDL en la sección siguiente.

### (c) Garantía de unicidad: dos capas — [ADR-003](docs/decisions/ADR-003-garantia-de-unicidad.md)

`employees-service` exige `email` y `numeroEmpleado` únicos. La garantía se
implementa en **dos capas complementarias**, cada una con un propósito distinto:

**Capa 1 — consulta previa** (`existsByEmailIgnoreCase`, `existsByNumeroEmpleado`
en `EmployeeService.register`). Cubre el caso común y permite responder un mensaje
preciso: `El email 'juan.perez@empresa.com' ya está registrado`. Esta capa es la
que produce **buenos mensajes**, no la que garantiza el invariante.

**Capa 2 — restricción `UNIQUE` en el esquema**, declarada en la migración
`V1__create_employees.sql`:

```sql
email           VARCHAR(255) NOT NULL UNIQUE,
employee_number VARCHAR(255) NOT NULL UNIQUE,
```

La consulta previa, por sí sola, deja abierta una **condición de carrera**: dos
peticiones con el mismo email que lleguen casi simultáneamente pueden *ambas* pasar
la comprobación (ninguna ve a la otra porque ninguna ha insertado todavía) y *ambas*
intentar insertar. Con un servicio multihilo —o varias réplicas— no es teórico. La
restricción `UNIQUE` es la única barrera que PostgreSQL hace cumplir de forma
atómica: la segunda inserción falla aunque haya superado la consulta.

Cuando esa carrera ocurre, JPA lanza `DataIntegrityViolationException`; el
`GlobalExceptionHandler` la captura y responde **`400` con mensaje descriptivo**
(`El email o número de empleado ya está registrado`), igual que la capa 1. El
cliente recibe el mismo código en ambos caminos y nunca ve un `500`.

En resumen: **no es "solo consulta previa"**. La consulta da el mensaje amigable;
la restricción garantiza la corrección. El `id` queda cubierto por la clave primaria.

---

## Creación y Evolución del Esquema de Base de Datos

En este sistema, la creación y evolución de las tablas no depende de scripts manuales ni de la máquina de un desarrollador en particular: **se ejecuta automáticamente al iniciar los contenedores**.

### ¿Quién crea las tablas y cuándo?

* **En `departments-service` (Node.js + Prisma):**
  Al iniciar el contenedor, el comando de arranque ejecuta:
  ```bash
  npx prisma migrate deploy && exec npx tsx src/server.ts
  ```
  Prisma inspecciona el directorio `prisma/migrations/`, detecta las migraciones pendientes y las aplica secuencialmente en la base `gestion_departamentos` antes de levantar el servidor HTTP.
* **En `employees-service` (Java + Spring Boot + Flyway):**
  Durante la fase de inicio del contexto de Spring Boot, **Flyway** detecta automáticamente las migraciones ubicadas en `src/main/resources/db/migration/` (como `V1__create_employees.sql`) y las aplica sobre `gestion_empleados`. Posteriormente, Hibernate valida que las entidades JPA coincidan con el esquema aplicado (`spring.jpa.hibernate.ddl-auto=validate`).

### Estrategia elegida: Herramienta de Migraciones Versionadas

Se evaluaron tres estrategias de inicialización de bases de datos:

| Estrategia | Cómo funciona | Ventajas | Desventajas / Limitaciones |
|---|---|---|---|
| **Script de inicialización (`init.sql`)** | Archivo `.sql` montado en `/docker-entrypoint-initdb.d/`. | Sencillo y explícito para la primera ejecución. | **"La trampa del script de inicialización"**: Solo se ejecuta cuando el volumen está completamente vacío. Si el esquema cambia, obliga a borrar el volumen (`docker compose down -v`) perdiendo todos los datos. |
| **Auto-DDL del ORM** (`ddl-auto=create/update`) | El framework genera las tablas al arrancar a partir del modelo. | Cómodo en etapas tempranas. | Peligroso: no hay trazabilidad de cambios, puede alterar o eliminar columnas con datos en producción y no controla índices ni secuencias avanzadas. |
| **Herramienta de migraciones (Elegida)** | Archivos SQL versionados y ordenados (Flyway y Prisma Migrate). | **Trazabilidad histórica completa, idempotencia, evolución sin pérdida de datos y compatible con CI/CD.** | Requiere configurar la herramienta en el ciclo de vida del servicio. |

### Justificación de la decisión y "La trampa del script de inicialización"

Se optó por la **Estrategia de Herramientas de Migraciones (Flyway y Prisma Migrate)** por las siguientes razones:

1. **Evita la trampa de `/docker-entrypoint-initdb.d/`:** En un proyecto donde los microservicios evolucionan continuamente (como agregar nuevos estados a los empleados o nuevas secuencias a departamentos), un script `init.sql` queda congelado en el volumen persistente una vez inicializado. Forzar a un compañero a ejecutar `docker compose down -v` cada vez que el esquema cambie borra todos los datos de prueba acumulados.
2. **Evolución no destructiva:** Las migraciones versionadas permiten alterar tablas existentes (`ALTER TABLE`, adición de columnas, restricciones `UNIQUE`, índices) aplicando incrementos (`V2__...`, nuevas carpetas de migración en Prisma) sin perder un solo registro existente.
3. **Idempotencia y seguridad:** Ambas herramientas gestionan su propia tabla de historial (`flyway_schema_history` y `_prisma_migrations`). Si una migración ya fue aplicada, no se repite; si es nueva, se ejecuta en una transacción.
4. **Validación estricta con ORM:** Al utilizar `hibernate.ddl-auto=validate`, aseguramos que Hibernate nunca modifique la estructura por su cuenta, garantizando que el código Java y el esquema SQL permanezcan sincronizados bajo control estricto.

---

## Salud y resiliencia

### Health checks y orden de inicialización
- Las bases `database-departamentos` y `database-empleados` usan
  `pg_isready -U <usuario> -d <base>` como health check.
- Los microservicios esperan a que sus dependencias estén `healthy` mediante
  `depends_on.condition: service_healthy` en Compose. Departamentos se comprueba en
  `GET /health` y Empleados en `GET /empleados/health`.
- Las imágenes de los dos microservicios incluyen `curl` y ejecutan `curl -f` contra
  sus endpoints de salud respetando los puertos de entorno.

### Resiliencia ante el desastre en la llamada inter-servicios

Al comunicar `employees-service` con `departments-service` vía HTTP para validar departamentos, se implementaron las dos protecciones esenciales contra fallos de red o caídas temporales:

1. **Timeout explícito:** Se configuró `SimpleClientHttpRequestFactory` con un tiempo de conexión máximo de **3 segundos** y de lectura de **5 segundos**. Esto impide que una lentitud o congelamiento en el servicio de departamentos bloquee indefinidamente los hilos de atención de empleados.
2. **Reintentos con espera exponencial creciente (Backoff):** Mediante Spring Retry (`@Retryable(maxAttempts = 4, backoff = @Backoff(delay = 1000, multiplier = 2.0))`), ante errores de conexión (`ResourceAccessException`) o fallos del servidor remoto (`HttpServerErrorException`), el cliente hace **4 intentos en total** (1 inicial + 3 reintentos) con esperas crecientes entre ellos:

   ```
   intento 1 ──(1 s)──► intento 2 ──(2 s)──► intento 3 ──(4 s)──► intento 4 ──► 503
   ```

   Un `RetryListener` (`RetryLoggingListener`) deja rastro de cada intento en el log. Salida real del test `DepartmentClientRetryTest`, que apunta el cliente a un puerto cerrado y verifica 4 intentos y un tiempo total ≥ 7 s:

   ```
   20:53:46.688  WARN  Intento 1 fallido en DepartmentClient.existsById: ResourceAccessException — Connection refused
   20:53:47.700  WARN  Intento 2 fallido en DepartmentClient.existsById: ResourceAccessException — Connection refused   (+1.0 s)
   20:53:49.721  WARN  Intento 3 fallido en DepartmentClient.existsById: ResourceAccessException — Connection refused   (+2.0 s)
   20:53:53.753  WARN  Intento 4 fallido en DepartmentClient.existsById: ResourceAccessException — Connection refused   (+4.0 s)
   20:53:53.753  ERROR Reintentos agotados en DepartmentClient.existsById tras 4 intentos
   ```

#### Decisión arquitectónica al agotar los reintentos: ¿Rechazar o aceptar como pendiente?

> **Decisión:** Se **rechaza la solicitud de registro devolviendo `503 Service Unavailable`** con el mensaje `"El servicio de departamentos no está disponible temporalmente"`, en lugar de aceptar al empleado en un estado "pendiente de validación".

**Justificación técnica:**
* **Consistencia e integridad de datos:** Aceptar un registro sin validar el departamento arriesga almacenar empleados con identificadores de departamento inexistentes o erróneos en la base de datos PostgreSQL, violando la integridad de dominio.
* **Preservación del modelo canónico:** El modelo del empleado requiere que el estado sea estrictamente `ACTIVO` en esta fase del sistema. Introducir un estado transitorio `PENDIENTE` rompería el contrato de la API.
* **Ausencia de infraestructura de reconciliación asíncrona:** Aceptar registros pendientes exigiría colas de mensajería (RabbitMQ/Kafka) o tareas programadas de conciliación en segundo plano que están fuera del alcance de este reto.
* **Estrategia Fail-Fast:** El cliente recibe un código `503` estándar que le indica claramente que el sistema no puede garantizar la validez del registro en ese instante y debe reintentar más tarde.

### Resiliencia en la persistencia (PostgreSQL)
Para fallos transitorios de PostgreSQL durante la ejecución, ambos microservicios implementan reintentos automáticos con espera exponencial y timeouts de conexión. Si la base continúa sin estar disponible tras los intentos, la petición responde `503` sin colapsar el proceso del microservicio.

## Pruebas con Postman y Newman

En [`postman/`](postman/) hay un environment y tres colecciones (Postman Collection v2.1):

| Archivo | Qué prueba |
|---|---|
| `system-hr.postman_environment.json` | Variables `empleadosBaseUrl` y `departamentosBaseUrl`. Las tres colecciones las usan; no hay URLs fijas |
| `system-hr-ecosistema.postman_collection.json` | **Flujo de integración completo** (punto 8 del enunciado): crea IT → crea E001 en IT → consulta → tres rechazos (email, número, departamento) → listados. Los pasos se encadenan con variables de colección |
| `empleados-service.postman_collection.json` | Contrato de `employees-service`: 201, duplicados 400, departamento inexistente 400, 404 exacto, listado |
| `departamentos-service.postman_collection.json` | Contrato de `departments-service`: 201, body inválido 400, 404 con id, listado |

Cada request lleva `pm.test()` de código de estado y de contenido, así que una
corrida completa en verde es evidencia automatizable de las pruebas.

### Importar en Postman

1. **Import** → arrastra los cuatro archivos de `postman/` a la vez.
2. Arriba a la derecha, selecciona el environment **System HR - local**.
3. Si el sistema corre en otros puertos, edita el environment (columna *Current value*), no las colecciones.
4. Abre una colección → **Run** → *Run collection*. Los requests van en orden.

**Precondición:** las colecciones crean registros con ids fijos (`IT`, `E001`,
`RRHH`, `E101`) y esperan `201` al crearlos. Ejecútalas sobre una base limpia:

```bash
docker compose down -v && docker compose up --build -d
```

Las tres colecciones usan ids distintos entre sí, así que pueden correr una tras
otra sin reiniciar la base.

### Ejecutar por línea de comandos con Newman

Newman es el runner de Postman para terminal; con `npx` no requiere instalación:

```bash
npx newman run postman/system-hr-ecosistema.postman_collection.json \
  -e postman/system-hr.postman_environment.json
```

```bash
npx newman run postman/empleados-service.postman_collection.json \
  -e postman/system-hr.postman_environment.json
```

```bash
npx newman run postman/departamentos-service.postman_collection.json \
  -e postman/system-hr.postman_environment.json
```

Para sobreescribir un puerto sin editar el environment (por ejemplo, si el 8080
está ocupado en tu máquina):

```bash
npx newman run postman/system-hr-ecosistema.postman_collection.json \
  -e postman/system-hr.postman_environment.json \
  --env-var empleadosBaseUrl=http://localhost:8090
```

Newman termina con código de salida distinto de cero si cualquier aserción falla,
así que sirve tal cual en un pipeline de CI.

### Resultado de la última ejecución

Las tres colecciones, corridas en ese orden sobre una base recién creada
(`down -v && up --build`), el 2026-09-15:

```console
$ npx newman run postman/system-hr-ecosistema.postman_collection.json -e postman/system-hr.postman_environment.json
→ 1. Crear departamento IT                        POST /departamentos   [201 Created]   √ √
→ 2. Crear empleado E001 en IT                    POST /empleados       [201 Created]   √ √ √
→ 3. Consultar empleado E001                      GET  /empleados/E001  [200 OK]        √ √
→ 4. POST con email de E001 duplicado             POST /empleados       [400 Bad Request] √ √
→ 5. POST con numeroEmpleado de E001 duplicado    POST /empleados       [400 Bad Request] √ √
→ 6. POST con departamentoId inexistente          POST /empleados       [400 Bad Request] √ √
→ 7. Consultar departamento IT                    GET  /departamentos/IT [200 OK]       √ √
→ 8. Listar departamentos                         GET  /departamentos   [200 OK]        √ √
→ 9. Listar empleados                             GET  /empleados       [200 OK]        √ √
┌─────────────────────────┬───────────────────┬───────────────────┐
│                         │          executed │            failed │
│                requests │                 9 │                 0 │
│              assertions │                19 │                 0 │
└─────────────────────────┴───────────────────┴───────────────────┘

$ npx newman run postman/empleados-service.postman_collection.json -e postman/system-hr.postman_environment.json
│                requests │                 8 │                 0 │
│              assertions │                16 │                 0 │

$ npx newman run postman/departamentos-service.postman_collection.json -e postman/system-hr.postman_environment.json
│                requests │                 5 │                 0 │
│              assertions │                10 │                 0 │
```

**Total: 22 requests, 45 aserciones, 0 fallos.**

---

## Reglas de organizacion

- Los cambios internos de un servicio se realizan dentro de su directorio en `services/`.
- Las migraciones y modelos de datos pertenecen al servicio dueno de la informacion.
- `infrastructure/` contiene configuracion de ejecucion, no logica de negocio.
- Las pruebas que requieren varios servicios pertenecen a `tests/`.
- Las variables locales y credenciales se mantienen en `.env`, nunca en el repositorio.

## Estado actual

La orquestacion completa incluye una base PostgreSQL independiente para cada servicio. `employees-service` conserva sus datos en `gestion_empleados` y `departments-service` en `gestion_departamentos`.
