# vacations-service

Microservicio de gestión de períodos y saldos de vacaciones. Está implementado con Node.js 22, TypeScript, Express, Prisma y PostgreSQL; publica y consume eventos a través de RabbitMQ.

## API y Swagger

La ruta canónica es `/vacaciones`; `/vacations` se conserva como alias.

| Recurso | Método y ruta | Descripción |
|---|---|---|
| Períodos | `POST /vacaciones` | Programa un período y publica `vacaciones.programadas` |
| Períodos | `GET /vacaciones` | Lista períodos |
| Períodos | `GET /vacaciones?empleadoId={id}` | Filtra por empleado |
| Períodos | `GET /vacaciones/{id}` | Consulta por ID |
| Períodos | `DELETE /vacaciones/{id}` | Cancela períodos cuya fecha de inicio aún es futura |
| Saldos | `GET /vacaciones/balances` | Lista saldos; admite `?estado=ACTIVO` o `?estado=RETIRADO` |
| Saldos | `GET /vacaciones/balances/{employeeId}` | Consulta el saldo de un empleado |

- Swagger UI: `http://localhost:8080/vacaciones/docs`
- Especificación OpenAPI JSON: `http://localhost:8080/vacaciones/openapi.json` (también disponible en `/api-docs`)

La especificación documenta el `200` esperado por el contrato del reto y el `201` que devuelve actualmente el `POST`. Asimismo, el reto solicita `400` para empleado inexistente, pero la implementación actual responde `404`; ambas respuestas están señaladas en OpenAPI.

## Justificación técnica: validación de empleados

El servicio valida la existencia del empleado consultando su réplica local, alimentada mediante eventos de RabbitMQ, en lugar de depender de una consulta REST síncrona a `empleados-service`. Esta estrategia prioriza la disponibilidad y autonomía del servicio: una caída o latencia del servicio de empleados no bloquea las operaciones de vacaciones y evita acoplamiento en tiempo de ejecución.

La contrapartida es la consistencia eventual: después de crear o retirar un empleado, la réplica puede tardar en reflejar el cambio hasta que se procese el evento. La validación consulta el estado local disponible en ese momento, no una lectura inmediata de la fuente autoritativa.

## Diversidad tecnológica del ecosistema

| Servicio / componente | Lenguaje de programación / framework |
|---|---|
| `api-gateway` | Java 17, Spring Boot, Spring Cloud Gateway |
| `employees-service` | Java 21, Spring Boot |
| `departments-service` | TypeScript, Node.js, Express, Prisma |
| `notifications-service` | TypeScript, Node.js, Express, Prisma |
| `profiles-service` | TypeScript, Node.js, Express, Prisma |
| `vacations-service` | TypeScript, Node.js, Express, Prisma |
| Pruebas de humo (`tests/reto3_smoke.py`) | Python 3 |
| Migraciones de base de datos | SQL, PostgreSQL |

El ecosistema incluye Java, TypeScript, Python y SQL. Python se utiliza en automatización de pruebas y SQL en las migraciones; no se presentan como microservicios adicionales.

## Catálogo de eventos del servicio

Los envelopes siguen el esquema `id`, `type`, `version`, `occurredAt`, `producer` y `data`.

### Publicado

`vacaciones.programadas` se publica en el exchange fanout `hr.events` tras programar un período. El payload `data` contiene `id`, `empleadoId`, `fechaInicio`, `fechaFin`, `estado` y `fechaCreacion`.

```json
{
  "id": "vac-1791490000000-1234",
  "type": "vacaciones.programadas",
  "version": "1.0",
  "occurredAt": "2026-10-08T20:00:00.000Z",
  "producer": "vacations-service",
  "data": {
    "id": "uuid-del-periodo",
    "empleadoId": "E401",
    "fechaInicio": "2026-11-01",
    "fechaFin": "2026-11-15",
    "estado": "PROGRAMADA",
    "fechaCreacion": "2026-10-08T20:00:00.000Z"
  }
}
```

### Consumidos

El consumidor escucha `vacations.queue` y procesa eventos de empleados para mantener `VacationBalance` actualizado:

- `empleado.creado`: inicializa el saldo local.
- `empleado.retirado`: marca el registro local como `RETIRADO` y conserva su historial.
- `empleado.actualizado`: actualiza los datos personales y conserva los días de vacaciones.

La tabla `eventos_procesados` persiste el ID del envelope y su timestamp. El procesamiento y el registro del ID se hacen en una transacción. Una repetición del mismo ID se omite, se registra en el log y recibe ACK.

## Pruebas HTTP: validaciones y cancelación

Las llamadas siguientes están escritas para PowerShell y usan el Gateway. Requieren servicios iniciados y, para crear períodos, un empleado existente en la réplica local. Las fechas se calculan dinámicamente.

```powershell
$base = "http://localhost:8080"
$empleado = "E401" # Sustituir por un ID existente en la réplica de vacaciones
$hoy = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
$inicio = (Get-Date).ToUniversalTime().AddDays(10).ToString("yyyy-MM-dd")
$fin = (Get-Date).ToUniversalTime().AddDays(15).ToString("yyyy-MM-dd")
```

**Fecha fin igual a fecha de inicio — espera `400 Bad Request`:**

```powershell
$body = @{ empleadoId = $empleado; fechaInicio = $inicio; fechaFin = $inicio } | ConvertTo-Json -Compress
curl.exe -i -X POST "$base/vacaciones" -H "Content-Type: application/json" --data-raw $body
```

**Fecha de inicio pasada — espera `400 Bad Request`:**

```powershell
$body = @{ empleadoId = $empleado; fechaInicio = "2000-01-01"; fechaFin = "2000-01-05" } | ConvertTo-Json -Compress
curl.exe -i -X POST "$base/vacaciones" -H "Content-Type: application/json" --data-raw $body
```

**Solapamiento — programar el período una vez y repetir una solicitud con fechas coincidentes. La segunda debe responder `400 Bad Request`:**

```powershell
$body = @{ empleadoId = $empleado; fechaInicio = $inicio; fechaFin = $fin } | ConvertTo-Json -Compress
curl.exe -i -X POST "$base/vacaciones" -H "Content-Type: application/json" --data-raw $body
curl.exe -i -X POST "$base/vacaciones" -H "Content-Type: application/json" --data-raw $body
```

**Crear y cancelar un período futuro:**

```powershell
$body = @{ empleadoId = $empleado; fechaInicio = $inicio; fechaFin = $fin } | ConvertTo-Json -Compress
$periodo = curl.exe -s -X POST "$base/vacaciones" -H "Content-Type: application/json" --data-raw $body | ConvertFrom-Json
$periodoId = $periodo.id
curl.exe -i -X DELETE "$base/vacaciones/$periodoId"
```

La cancelación válida responde `200 OK` y devuelve el período en estado `CANCELADA`. Para verificar que un período inexistente responde `404`, se puede usar un UUID que no exista:

```powershell
curl.exe -i -X DELETE "$base/vacaciones/00000000-0000-0000-0000-000000000000"
```

Una cancelación cuya `fechaInicio` sea hoy o anterior responde `400` con el mensaje `No es posible cancelar un período de vacaciones que ya ha iniciado o finalizado`.

**Empleado inexistente:** el contrato del reto solicita `400`, pero el comportamiento implementado actualmente devuelve `404`. Para verificarlo, enviar un `POST` con un `empleadoId` que no esté en la réplica local y fechas futuras válidas.

## Prueba de deduplicación desde RabbitMQ Management

1. Abrir `http://localhost:15672`, entrar en **Exchanges** y seleccionar `hr.events`.
2. En **Publish message**, usar routing key vacía y `Content type` `application/json`.
3. Publicar un envelope `empleado.creado` con un `id` estable y un `data.id` nuevo, por ejemplo:

   ```json
   {
     "id": "dedup-vacaciones-001",
     "type": "empleado.creado",
     "version": "1.0",
     "occurredAt": "2026-10-08T20:00:00.000Z",
     "producer": "employees-service",
     "data": {
       "id": "DEDUP-001",
       "nombre": "Prueba",
       "apellido": "Deduplicacion",
       "email": "dedup@example.test",
       "departamentoId": "D001"
     }
   }
   ```

4. Publicar exactamente el mismo JSON una segunda vez, conservando el mismo `id` del envelope.
5. Consultar `docker compose logs vacations-service`. El segundo mensaje debe registrar `Evento duplicado omitido: dedup-vacaciones-001`; en la réplica debe existir una sola aplicación del evento. La tabla `eventos_procesados` guarda una única fila para ese ID.

Para probar `empleado.retirado`, crear primero el empleado en la réplica con un evento `empleado.creado`, enviar un `empleado.retirado` con un envelope ID nuevo y publicarlo dos veces sin cambiar ese ID.

## Ejecución local con Docker Compose

```bash
docker compose up --build -d db-vacaciones rabbitmq vacations-service api-gateway
docker compose ps vacations-service db-vacaciones rabbitmq
docker compose logs -f vacations-service
```

`vacations-service` y `db-vacaciones` solo se exponen en la red interna de Compose; el acceso HTTP externo se realiza a través del API Gateway en el puerto `8080`.
