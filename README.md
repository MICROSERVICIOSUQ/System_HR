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
- Códigos de respuesta HTTP posibles (`200 OK`, `201 Created`, `204 No Content`, `400 Bad Request`, `404 Not Found`, `409 Conflict`, `503 Service Unavailable`).
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
2. **Reintentos con espera exponencial creciente (Backoff):** Mediante Spring Retry (`@Retryable`), ante errores de conexión (`ResourceAccessException`) o fallos del servidor remoto (`HttpServerErrorException`), el cliente reintenta hasta un máximo de **3 intentos** con retroceso exponencial:
   $$\text{Intento 1: } 1s \longrightarrow \text{Intento 2: } 2s \longrightarrow \text{Intento 3: } 4s$$

#### Decisión arquitectónica al agotar los reintentos: ¿Rechazar o aceptar como pendiente?

> **Decisión:** Se **rechaza la solicitud de registro devolviendo `503 Service Unavailable`** con el mensaje `"El servicio de departamentos no está disponible temporalmente"`, en lugar de aceptar al empleado en un estado "pendiente de validación".

**Justificación técnica:**
* **Consistencia e integridad de datos:** Aceptar un registro sin validar el departamento arriesga almacenar empleados con identificadores de departamento inexistentes o erróneos en la base de datos PostgreSQL, violando la integridad de dominio.
* **Preservación del modelo canónico:** El modelo del empleado requiere que el estado sea estrictamente `ACTIVO` en esta fase del sistema. Introducir un estado transitorio `PENDIENTE` rompería el contrato de la API.
* **Ausencia de infraestructura de reconciliación asíncrona:** Aceptar registros pendientes exigiría colas de mensajería (RabbitMQ/Kafka) o tareas programadas de conciliación en segundo plano que están fuera del alcance de este reto.
* **Estrategia Fail-Fast:** El cliente recibe un código `503` estándar que le indica claramente que el sistema no puede garantizar la validez del registro en ese instante y debe reintentar más tarde.

### Resiliencia en la persistencia (PostgreSQL)
Para fallos transitorios de PostgreSQL durante la ejecución, ambos microservicios implementan reintentos automáticos con espera exponencial y timeouts de conexión. Si la base continúa sin estar disponible tras los intentos, la petición responde `503` sin colapsar el proceso del microservicio.

## Reglas de organizacion

- Los cambios internos de un servicio se realizan dentro de su directorio en `services/`.
- Las migraciones y modelos de datos pertenecen al servicio dueno de la informacion.
- `infrastructure/` contiene configuracion de ejecucion, no logica de negocio.
- Las pruebas que requieren varios servicios pertenecen a `tests/`.
- Las variables locales y credenciales se mantienen en `.env`, nunca en el repositorio.

## Estado actual

La orquestacion completa incluye una base PostgreSQL independiente para cada servicio. `employees-service` conserva sus datos en `gestion_empleados` y `departments-service` en `gestion_departamentos`.
