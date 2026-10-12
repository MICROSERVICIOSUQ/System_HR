# RabbitMQ — Infraestructura de Mensajería System_HR

Configuración de RabbitMQ para el sistema de mensajería asíncrona del proyecto System_HR.

---

## Conceptos fundamentales

### Exchange

Un **Exchange** es el punto de entrada de mensajes en RabbitMQ. Los productores nunca publican mensajes directamente en una cola; siempre lo hacen a un exchange. El exchange es responsable de decidir hacia qué colas enrutar cada mensaje, según su tipo y las reglas de binding.

```
Producer ──► Exchange ──► (reglas de routing) ──► Cola(s)
```

### Queue (Cola)

Una **Queue** es el buffer donde se acumulan los mensajes hasta que un consumidor los procesa. Las colas son **durables** en este proyecto, lo que significa que sobreviven a un reinicio de RabbitMQ sin perder los mensajes no procesados.

### Binding

Un **Binding** es la regla que conecta un exchange con una cola. Le dice al exchange: "cuando recibas un mensaje, envía una copia a esta cola". En un exchange de tipo `fanout`, el binding no necesita una routing key, ya que todos los mensajes se envían a todas las colas enlazadas.

---

## Por qué Fanout para System_HR

En este sistema, cuando ocurre un evento de dominio (por ejemplo, `empleado.creado`), **todos los microservicios interesados deben recibir el mismo evento** simultáneamente:

- `profiles-service` necesita crear el perfil del empleado.
- `notifications-service` necesita enviar un correo de bienvenida.
- `vacations-service` necesita inicializar el saldo de vacaciones.

El exchange **Fanout** es la elección natural porque:

| Criterio | Fanout | Direct/Topic |
|---|---|---|
| Todos los consumidores reciben el evento | ✅ Automático | ❌ Requiere routing keys por cada par |
| Agregar un nuevo consumidor | Sin cambiar el productor | Requiere configurar nueva routing key |
| Desacoplamiento | Total | Parcial |
| Complejidad | Baja | Media-Alta |

Con Fanout, `employees-service` publica **un único mensaje** al exchange `hr.events` y RabbitMQ se encarga de entregarlo a todas las colas suscritas. El productor no sabe cuántos ni cuáles consumidores existen.

---

## Configuración de este proyecto

```
hr.events (fanout, durable)
    │
    ├──── profiles.queue      (durable)
    ├──── notifications.queue (durable)
    └──── vacations.queue     (durable)
```

### Diagrama de flujo completo

```
employees-service ──► hr.events ──┬──► profiles.queue      ──► profiles-service
                    (fanout)      ├──► notifications.queue  ──► notifications-service
                                  └──► vacations.queue      ──► vacations-service

vacations-service ──► hr.events ──┬──► profiles.queue      ──► profiles-service
                    (fanout)      └──► notifications.queue  ──► notifications-service
```

---

## Archivos de configuración

### `definitions.json`

Cargado automáticamente por RabbitMQ al arrancar mediante la variable de entorno
`RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS` y el volumen montado en
`/etc/rabbitmq/definitions.json`.

> **Nota técnica:** Los usuarios y contraseñas son responsabilidad del entorno (`docker-compose.yml`). El archivo `definitions.json` únicamente define recursos AMQP: exchanges, queues y bindings.

Contiene exclusivamente:

- Exchange `hr.events` (fanout, durable)
- Colas `profiles.queue`, `notifications.queue`, `vacations.queue` (durables)
- Bindings de las tres colas al exchange con routing key vacía (`""`)

---

## Acceso al panel de administración

| Dato | Valor |
|---|---|
| URL | http://localhost:15672 |
| Usuario | `admin` |
| Contraseña | `admin` |
| Puerto AMQP | `5672` |

Desde el panel puedes verificar en tiempo real:
- El exchange `hr.events` y su tipo fanout.
- Las tres colas y la cantidad de mensajes pendientes.
- Los bindings entre el exchange y las colas.
- Las conexiones activas de productores y consumidores.

---

## Comandos útiles

```bash
# Ver logs de RabbitMQ
docker compose logs rabbitmq

# Verificar que el servicio está healthy
docker compose ps rabbitmq

# Publicar un mensaje de prueba desde el panel
# Ir a Exchanges > hr.events > Publish message
# Payload: cualquier JSON del catálogo en shared-events/catalog/
```
