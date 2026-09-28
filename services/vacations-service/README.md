# Servicio de Vacaciones (vacations-service)

Microservicio desarrollado con **Node.js 22**, **TypeScript**, **Express**, **Prisma** y **PostgreSQL**.

Funciona como un **consumidor asíncrono** y modelo de consulta en una arquitectura **CQRS**: mantiene el saldo e historial de vacaciones de cada empleado construido **exclusivamente** mediante eventos de RabbitMQ. Nunca realiza llamadas HTTP a otros microservicios.

---

## Arquitectura

```text
[employees-service]  (Productor)
        │
        ▼ (Fanout)
   [hr.events] (Exchange)
        │
        ├──► [notifications.queue]  → notifications-service
        │
        ├──► [profiles.queue]       → profiles-service
        │
        └──► [vacations.queue]       ← ★ ESTE SERVICIO
                     │
                     ▼ (amqplib — sin modificar topología)
             [vacations-service]
                     │
                     ▼ (Prisma ORM)
             [PostgreSQL: database-vacaciones]
                     └── Tabla: VacationBalance
                           ├── employeeId  (UNIQUE)
                           └── lastEventId (UNIQUE — idempotencia)
```

### Principios CQRS aplicados

| Aspecto | Detalle |
|---|---|
| **Escritura (Command)** | Exclusivamente mediante eventos asíncronos de RabbitMQ (`vacations.queue`) |
| **Lectura (Query)** | API REST interna sobre PostgreSQL (`database-vacaciones`) |
| **Consistencia** | Eventual — el balance de vacaciones se actualiza al procesar eventos |
| **Independencia** | Totalmente desacoplado de `employees-service` (cero llamadas HTTP síncronas) |

---

## Modelo de Datos — `VacationBalance`

| Campo | Tipo | Restricción / Default | Descripción |
|---|---|---|---|
| `id` | UUID (String) | PK (`@id @default(uuid())`) | Identificador interno |
| `employeeId` | String | `UNIQUE` | Identificador del empleado (clave de negocio) |
| `nombre` | String | NOT NULL | Nombre del empleado |
| `apellido` | String | NOT NULL | Apellido del empleado |
| `email` | String | NOT NULL | Correo electrónico |
| `departamentoId` | String | NOT NULL | ID del departamento |
| `diasDisponibles` | Integer | NOT NULL, `DEFAULT 15` | Días hábiles de vacaciones disponibles |
| `diasTomados` | Integer | NOT NULL, `DEFAULT 0` | Días de vacaciones ya disfrutados |
| `estadoEmpleado` | String | NOT NULL, `DEFAULT 'ACTIVO'` | Estado del empleado: `ACTIVO` \| `RETIRADO` |
| `lastEventId` | String | `UNIQUE` | UUID del último evento aplicado (garantía de idempotencia) |
| `updatedAt` | DateTime | `@updatedAt` | Timestamp de última modificación |

---

## Eventos Consumidos de RabbitMQ

El servicio consume exclusivamente de la cola `vacations.queue` sin alterar exchanges ni colas preexistentes.

### 1. `empleado.creado`
- Crea un nuevo registro en `VacationBalance`.
- Inicializa con `diasDisponibles = 15`, `diasTomados = 0`, `estadoEmpleado = ACTIVO`.
- Guarda el `lastEventId`.

### 2. `empleado.actualizado`
- Actualiza únicamente: `nombre`, `apellido`, `email`, `departamentoId`.
- **NO modifica** `diasDisponibles` ni `diasTomados`.
- Actualiza el `lastEventId`.

### 3. `empleado.retirado`
- Actualiza: `estadoEmpleado = RETIRADO`.
- **NO elimina** el registro de la base de datos (conserva el saldo histórico).
- Actualiza el `lastEventId`.

---

## Idempotencia

Para evitar duplicación y efectos adversos por reentrega de mensajes en RabbitMQ:
1. **Verificación previa**: Antes de procesar cualquier evento, busca si `lastEventId` ya existe en la base de datos.
2. **Si existe**:
   - Registra `[VACATIONS] [DUPLICADO] Evento ya procesado...`.
   - Envía `ACK` a RabbitMQ.
   - No modifica la base de datos.
3. **Si no existe**:
   - Aplica la mutación correspondiente.
   - Guarda el `lastEventId`.
   - Envía `ACK` a RabbitMQ.
4. **Protección a nivel de base de datos**: Restricción `UNIQUE` sobre `lastEventId` para evitar carreras entre réplicas.

---

## Formato de Logs

Todos los logs siguen el prefijo estándar:
- `[VACATIONS] Mensaje recibido | UUID: ... | Evento: ... | Empleado: ...`
- `[VACATIONS] [CREADO] Registro de vacaciones inicializado...`
- `[VACATIONS] [ACTUALIZADO] Información de empleado actualizada (días de vacaciones intactos)...`
- `[VACATIONS] [RETIRADO] Empleado marcado como RETIRADO (saldo histórico conservado)...`
- `[VACATIONS] [DUPLICADO] Evento ya procesado anteriormente...`
- `[VACATIONS] [ERROR] Excepción procesando evento...`

---

## Variables de Entorno

| Variable | Descripción | Valor por defecto en Docker |
|---|---|---|
| `PORT` | Puerto HTTP del servicio | `8084` |
| `DATABASE_URL` | Cadena de conexión PostgreSQL | `postgresql://postgres:postgres@database-vacaciones:5432/gestion_vacaciones` |
| `RABBITMQ_HOST` | Host del broker RabbitMQ | `rabbitmq` |
| `RABBITMQ_PORT` | Puerto AMQP | `5672` |
| `RABBITMQ_USER` | Usuario RabbitMQ | `admin` |
| `RABBITMQ_PASSWORD` | Contraseña RabbitMQ | `admin` |

---

## API REST

### 1. Healthcheck
```http
GET /health
```
Respuesta:
```json
{
  "status": "ok",
  "service": "vacations"
}
```

### 2. Listar todos los saldos de vacaciones
```http
GET /vacations
```

### 3. Filtrar por estado del empleado
```http
GET /vacations?estado=ACTIVO
GET /vacations?estado=RETIRADO
```

### 4. Consultar saldo de un empleado por ID
```http
GET /vacations/:employeeId
```

---

## Pruebas de Funcionamiento

### 1. Construir y levantar el contenedor
```bash
docker compose up --build -d vacations-service
```

### 2. Verificar estado y logs
```bash
docker compose ps vacations-service
docker compose logs -f vacations-service
```

### 3. Probar el endpoint de salud
```bash
docker compose exec api-gateway curl -s http://vacations-service:8084/health
```

### 4. Consultar saldos de vacaciones
```bash
docker compose exec api-gateway curl -s http://vacations-service:8084/vacations
docker compose exec api-gateway curl -s http://vacations-service:8084/vacations?estado=ACTIVO
docker compose exec api-gateway curl -s http://vacations-service:8084/vacations?estado=RETIRADO
```
