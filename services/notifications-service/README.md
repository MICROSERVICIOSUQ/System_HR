# Servicio de Notificaciones (notifications-service)

Microservicio desarrollado con **Node.js 22**, **TypeScript**, **Express**, **Prisma** y **PostgreSQL**.
Actúa como **consumidor asíncrono** de RabbitMQ, procesando los eventos del dominio de empleados para generar y almacenar el historial de notificaciones con garantía de **idempotencia**.

---

## Arquitectura

El microservicio no se comunica directamente con `employees-service`. Se suscribe exclusivamente a la cola `notifications.queue`, la cual recibe mensajes enrutados automáticamente desde el exchange fanout `hr.events`.

```text
[employees-service]
        │
        ▼ (Fanout)
   [hr.events] (Exchange)
        │
        ├──► [notifications.queue]
        │            │
        │            ▼ (amqplib)
        │    [notifications-service]
        │            │
        │            ▼ (Prisma ORM)
        │    [PostgreSQL: database-notificaciones]
        │            └── Tabla: NotificationHistory (messageId UNIQUE)
        │
        ├──► [profiles.queue]
        └──► [vacations.queue]
```

---

## Cola y Eventos Consumidos

* **Cola consumida:** `notifications.queue`
* **Exchange de origen:** `hr.events`
* **Eventos procesados:**
  * `empleado.creado`
  * `empleado.actualizado`
  * `empleado.retirado`
* **Eventos ignorados:** Cualquier otro tipo de evento se confirma (`ACK`) y se descarta sin registrar en base de datos.

---

## Garantía de Idempotencia

RabbitMQ garantiza entrega *at-least-once*, lo que puede ocasionar reentregas por desconexión o reintentos de red. Para asegurar que ningún evento se procese o notifique dos veces:

1. **Restricción a nivel de base de datos:** El campo `messageId` (UUID v4 del evento) posee una restricción `UNIQUE` en la tabla `NotificationHistory`.
2. **Comprobación previa:** Antes de registrar la notificación, el servicio consulta si el `messageId` ya fue procesado:
   * Si **existe:** Se registra una advertencia en los logs (`[DUPLICADO]`) y se envía inmediatamente el `ACK` a RabbitMQ sin volver a insertar.
   * Si **no existe:** Se persiste el registro, se registra en los logs (`[ÉXITO]`) y se confirma el `ACK`.

---

## Endpoints de la API REST

El microservicio expone un puerto interno a la red Docker (`expose: 8082`). No está publicado por el API Gateway en esta fase.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/health` | Estado del microservicio (`{"status":"ok","service":"notifications"}`) |
| GET | `/notifications` | Lista completa del historial de notificaciones procesadas (orden descendente) |
| GET | `/notifications/:messageId` | Consulta una notificación por el UUID del mensaje de evento (`200` o `404`) |

---

## Variables de Entorno

| Variable | Descripción | Valor Docker Compose | Valor Local |
|---|---|---|---|
| `PORT` | Puerto HTTP del servidor Express | `8082` | `8082` |
| `DATABASE_URL` | Cadena de conexión PostgreSQL | `postgresql://postgres:postgres@database-notificaciones:5432/gestion_notificaciones` | `postgresql://postgres:postgres@localhost:5434/gestion_notificaciones` |
| `RABBITMQ_HOST` | Host del broker RabbitMQ | `rabbitmq` | `localhost` |
| `RABBITMQ_PORT` | Puerto AMQP del broker | `5672` | `5672` |
| `RABBITMQ_USER` | Usuario de RabbitMQ | `admin` | `admin` |
| `RABBITMQ_PASSWORD` | Contraseña de RabbitMQ | `admin` | `admin` |

---

## Cómo Probar el Consumo desde RabbitMQ

### 1. Publicar un evento desde el panel de RabbitMQ
1. Accede a **http://localhost:15672** con las credenciales `admin` / `admin`.
2. Navega a **Exchanges** > **hr.events** > **Publish message**.
3. Deja `Routing key` vacía.
4. En `Payload`, pega el siguiente JSON de prueba:

```json
{
  "id": "e1f2a3b4-c5d6-7890-abcd-ef1234567890",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-27T23:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "EMP-900",
    "nombre": "Elena",
    "apellido": "García",
    "email": "elena.garcia@empresa.com",
    "departamentoId": "IT",
    "cargo": "DevOps Engineer",
    "fechaIngreso": "2026-09-27",
    "fechaRetiro": null
  }
}
```

5. Pulsa **Publish message**.
6. Observa los logs del contenedor de notificaciones:
   ```powershell
   docker compose logs -f notifications-service
   ```
   Verás:
   ```text
   [NOTIFICATIONS] Mensaje recibido | UUID: e1f2a3b4-c5d6-7890-abcd-ef1234567890 | Tipo: empleado.creado
   [NOTIFICATIONS] [ÉXITO] Notificación registrada | UUID: e1f2a3b4-c5d6-7890-abcd-ef1234567890 | Empleado: EMP-900 (Elena García)
   ```

### 2. Verificar la Idempotencia
1. Vuelve a pulsar **Publish message** en RabbitMQ con el mismo payload (mismo `id`).
2. Revisa los logs de `notifications-service`:
   ```text
   [NOTIFICATIONS] Mensaje recibido | UUID: e1f2a3b4-c5d6-7890-abcd-ef1234567890 | Tipo: empleado.creado
   [NOTIFICATIONS] [DUPLICADO] Mensaje ya procesado anteriormente | UUID: e1f2a3b4-c5d6-7890-abcd-ef1234567890
   ```
   El mensaje se confirma con ACK sin duplicar registros en la base de datos.

### 3. Consultar la API de Notificaciones
Dentro de la red interna o ejecutando en el contenedor:
```powershell
# Consultar todas las notificaciones
docker compose exec notifications-service curl -s http://localhost:8082/notifications

# Consultar por messageId
docker compose exec notifications-service curl -s http://localhost:8082/notifications/e1f2a3b4-c5d6-7890-abcd-ef1234567890
```
