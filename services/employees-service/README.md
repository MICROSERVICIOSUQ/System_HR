# Servicio de empleados

API REST con Java 21, Spring Boot, JPA y PostgreSQL. Registra y consulta empleados, verifica duplicados y consulta a departamentos antes de guardar.

## Acceso

URL pública: `http://localhost:8080`, mediante el API Gateway. El servicio no publica su puerto directamente al host.

| Método | Ruta pública | Resultado principal |
|---|---|---|
| GET | `/empleados/health` | 200: salud del servicio |
| GET | `/empleados/circuit-breaker` | 200: estado y contadores del circuito |
| POST | `/empleados` | 201: empleado creado |
| GET | `/empleados` | 200: listado |
| GET | `/empleados/{id}` | 200 o 404 |

La salud del servicio es independiente de la disponibilidad de departamentos; el estado de esa dependencia se consulta en el endpoint del Circuit Breaker.

## Datos del registro

Son obligatorios `id`, `nombre`, `apellido`, `email`, `numeroEmpleado`, `cargo`, `area`, `departamentoId` y `fechaIngreso`.

`estado` es opcional al crear. El servicio asigna `ACTIVO`; un valor fuera del enum se rechaza por formato inválido.

El ID lo suministra el cliente. El email y el número de empleado deben ser únicos. La validación de departamento sí consulta a `departments-service`.

## Crear un empleado

Primero debe existir el departamento `IT`. Desde el host:

```bash
curl -i -X POST http://localhost:8080/empleados \
  -H 'Content-Type: application/json' \
  -d '{
    "id":"E001",
    "nombre":"Ana",
    "apellido":"Pérez",
    "email":"ana.perez@example.com",
    "numeroEmpleado":"EMP-001",
    "cargo":"Desarrolladora",
    "area":"Tecnología",
    "departamentoId":"IT",
    "fechaIngreso":"2026-09-22"
  }'
```

Devuelve `201 Created` con el empleado registrado. Repetir este ejemplo provoca un rechazo por duplicado.

## Errores

| Código | Motivo |
|---|---|
| 400 | Datos inválidos, duplicados o departamento inexistente |
| 404 | Empleado inexistente o ruta interna no encontrada |
| 503 | Departamentos no disponible, circuito abierto o persistencia no disponible |

Los errores incluyen `timestamp`, `status`, `error`, `message` y `path`. Los errores de validación añaden el detalle por campo.

## Flujo de registro y resiliencia

```text
Validación de campos
    → verificación de email, número e ID
    → DepartmentClient: Circuit Breaker
        → DepartmentHttpClient: HTTP con reintentos
    → guardar como ACTIVO
```

El circuito protege exclusivamente la consulta HTTP. Los duplicados y los errores de PostgreSQL no cuentan como fallos de departamentos.

Resilience4j utiliza una ventana de 3 operaciones, un mínimo de 3 y un umbral de fallos del 100 %. Espera 30 segundos en OPEN y permite una operación en HALF_OPEN. Una operación incluye hasta cuatro intentos HTTP, con esperas de 1, 2 y 4 segundos. Los timeouts son 3 segundos para conectar y 5 para leer.

Un 404 de departamentos significa que el servicio respondió correctamente: el registro devuelve 400 por departamento inexistente y el circuito puede cerrarse durante la recuperación.

## Fallback

Ante indisponibilidad o circuito abierto, se devuelve `503` y no se guarda el empleado. Se prioriza validar la referencia al departamento antes de persistir. No existen registros pendientes que reconciliar.

## Observación

```bash
curl -s http://localhost:8080/empleados/circuit-breaker
docker compose logs -f employees-service
```

El endpoint devuelve el nombre del circuito, estado y contadores. Los logs muestran los reintentos y cambios de estado.

## Ejecución y pruebas

El sistema completo se inicia con `docker compose up --build -d` desde la raíz. Compose proporciona la base de datos y la URL interna de departamentos. Flyway administra el esquema y Hibernate lo valida.

Con Java 21 y Maven, desde la carpeta del servicio:

```bash
mvn test
```

Las pruebas verificadas cubren endpoints, validaciones, reintentos, apertura, rechazo sin llamadas a la red, recuperación, fallo de la llamada de prueba y aislamiento de errores de negocio.

OpenAPI y Swagger siguen siendo rutas internas del servicio; actualmente el Gateway no las publica. Para la prueba integral del Reto 3, consulta `tests/README.md` desde la raíz del repositorio.

## Caso alternativo — Caché de contingencia

Como mejora para la resiliencia, el sistema cuenta con un caché temporal de información de departamentos.

* **Propósito:** Mantener temporalmente la capacidad de validar departamentos durante una interrupción de la comunicación con `departments-service`.
* **Ubicación:** Integrado en el `DepartmentClient` dentro de `employees-service`.
* **TTL:** 30 minutos (configurable vía `departments.cache.ttl-minutes`).
* **Fuente de verdad:** El servicio real y su base de datos. El caché se alimenta estrictamente de las respuestas exitosas de `departments-service`.
* **Cuándo se utiliza:** Únicamente como fallback tras agotar los reintentos HTTP o cuando el Circuit Breaker está abierto (`OPEN`). 
* **Cuándo NO se utiliza:** Nunca se utiliza como primera opción frente al servicio disponible. Además, un `404` (departamento inexistente) del servicio real no usa el caché porque constituye una comunicación exitosa.
* **Comportamiento ante cache miss:** Devuelve `503 Service Unavailable`, manteniendo el comportamiento original.
* **Comportamiento ante expiración:** Si la entrada superó su TTL, no se utiliza y devuelve `503`.
* **Recuperación del servicio:** Cuando el servicio real se recupera, las consultas vuelven a pasar por él (estado `HALF_OPEN` -> `CLOSED`) y el caché se actualiza.
* **Relación con Circuit Breaker:** Funciona de forma conjunta y posterior al CB. El CB sigue protegiendo la red, y el caché actúa en memoria cuando el CB determina la indisponibilidad.
* **Consistencia eventual:** Durante la ventana de caída, el servicio puede permitir registros si el caché está válido pero el departamento fue borrado de la base de datos de origen en ese intermedio. Esta limitación de consistencia eventual es asumida a favor de la disponibilidad.
