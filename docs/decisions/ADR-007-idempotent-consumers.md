# ADR-007: Idempotencia en Consumidores mediante Event ID

## Estado

Aceptada — 2026-09-28

## Contexto

En sistemas distribuidos basados en mensajería, RabbitMQ garantiza la semántica de entrega **"al menos una vez" (*at least once delivery*)**. Esto significa que bajo condiciones normales un mensaje se entrega una sola vez, pero ante fallos de red transitorios, reconexiones de clientes, reinicios abruptos de contenedores o demoras en la confirmación de ACK, el broker puede reentregar un mensaje que ya había sido procesado previamente por el consumidor.

Si las operaciones de los consumidores no fueran idempotentes, procesar un mensaje duplicado provocaría:
* Enviar múltiples correos o duplicar registros de bienvenida en `notifications-service`.
* Intentar insertar perfiles repetidos en `profiles-service`, causando errores de clave duplicada o corrupción de estado.
* Alterar o restablecer indebidamente los días de vacaciones en `vacations-service`.

Por lo tanto, era imperativo garantizar que procesar el mismo mensaje dos o más veces produzca exactamente el mismo resultado que procesarlo una sola vez.

## Decisión

Implementar **idempotencia en el consumidor basada en identificadores de eventos (*Event ID*)**:

1. **Sobre estandarizado:** Todo evento emitido cuenta con un identificador único global (`id` en formato UUID v4) generado por el productor en el momento de la emisión.
2. **Persistencia del identificador:** Cada consumidor almacena el identificador del evento (`lastEventId` o `messageId`) en su tabla de base de datos correspondiente, protegido por una restricción `UNIQUE`.
3. **Verificación previa:** Antes de ejecutar cualquier mutación o lógica de negocio, el consumidor consulta si el `eventId` ya existe en la base de datos.
4. **Tratamiento de duplicados:**
   * Si el identificador ya existe: el servicio emite un registro estructurado con el estado `[DUPLICADO]`, no altera la base de datos y envía inmediatamente un `ACK` a RabbitMQ para retirar el mensaje de la cola.
   * Si el identificador no existe: ejecuta la operación de negocio, almacena el `eventId` y confirma la recepción con `ACK`.

## Consecuencias

### Positivas
* **Tolerancia a reentregas:** El sistema tolera reintentos de red y reentregas de RabbitMQ sin duplicar entidades de negocio ni corromper datos históricos.
* **Seguridad transaccional:** La combinación de verificación previa en código y restricción `UNIQUE` en la base de datos previene tanto reintentos secuenciales como condiciones de carrera entre réplicas concurrentes (control de excepciones `P2002`).
* **Trazabilidad y auditoría:** Facilita el diagnóstico mediante registros claros (`[DUPLICADO]`) que documentan reentregas sin generar falsas alarmas de error.
* **Consistencia eventual garantizada:** Los consumidores logran un estado final consistente y predecible independientemente del número de veces que se procese un mensaje.

### Negativas
* **Sobrecarga de consulta:** Cada mensaje entrante requiere al menos una consulta adicional de lectura en la base de datos para verificar la existencia del evento.
* **Espacio de almacenamiento:** Requiere persistir una columna adicional (`lastEventId` o tabla de mensajes procesados) e índices en cada base de datos de los consumidores.

## Alternativas consideradas

* **Eliminar duplicados manualmente mediante scripts periódicos:** Descartada. Es una solución reactiva que no evita efectos colaterales en tiempo real (por ejemplo, notificaciones duplicadas enviadas inmediatamente al usuario) y traslada una carga operativa inadmisible a los administradores.
* **Confiar únicamente en el mecanismo ACK a nivel de red:** Descartada. El ACK solo confirma que el socket recibió o envió la confirmación; si la red o el servicio fallan un milisegundo antes de que el broker registre el ACK, la reentrega es inevitable según la especificación AMQP.
* **No implementar idempotencia (ignorar reentregas):** Descartada. Incompatible con los requerimientos de calidad arquitectónica y estabilidad de un sistema empresarial de Recursos Humanos.
