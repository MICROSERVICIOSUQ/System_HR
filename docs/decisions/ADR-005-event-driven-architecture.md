# ADR-005: Adopción de una Arquitectura Orientada a Eventos

## Estado

Aceptada — 2026-09-28

## Contexto

El servicio `employees-service` gestiona el ciclo de vida del personal (altas, modificaciones y retiros). Otros dominios dentro del sistema necesitan reaccionar ante estos cambios:
* `notifications-service`: debe enviar correos de bienvenida o avisos administrativos.
* `profiles-service`: debe mantener un modelo de consulta optimizado y desnormalizado (Read Model CQRS).
* `vacations-service`: debe inicializar y custodiar el saldo de días de vacaciones correspondientes.

Si `employees-service` invocara directamente a cada uno de estos servicios vía HTTP síncrono, asumiría el rol de orquestador central, conociendo detalles de implementación y dependencias de otros dominios, violando el principio de responsabilidad única y acoplando fuertemente el ciclo de vida del sistema.

## Decisión

Adoptar una **Arquitectura Orientada a Eventos (Event-Driven Architecture - EDA)** para la propagación de cambios de estado entre microservicios.

### Flujo de Ejecución

1. **Persistencia local:** `employees-service` procesa la petición de negocio, valida los datos y persiste la entidad en su base de datos PostgreSQL (`database-empleados`).
2. **Publicación del evento:** Inmediatamente después de confirmar la transacción local, publica un evento de dominio (`empleado.creado`, `empleado.actualizado` o `empleado.retirado`) encapsulado en un *Event Envelope* estandarizado hacia el exchange de RabbitMQ.
3. **Distribución asíncrona:** RabbitMQ enruta el evento a las colas suscritas de forma independiente.
4. **Procesamiento y persistencia autónoma:** Cada consumidor (`notifications-service`, `profiles-service`, `vacations-service`) extrae el mensaje de su respectiva cola y ejecuta su lógica particular, persistiendo los resultados exclusivamente en su propia base de datos.

## Consecuencias

### Positivas
* **Bajo acoplamiento:** `employees-service` no conoce qué servicios consumen los eventos ni qué operaciones ejecutan.
* **Extensibilidad y evolución independiente:** Se pueden incorporar nuevos consumidores en el futuro (por ejemplo, servicios de nómina, capacitaciones o auditoría) sin modificar una sola línea de código en `employees-service`.
* **Escalabilidad y resiliencia:** Cada microservicio procesa eventos a su propio ritmo. Si un consumidor se detiene o satura temporalmente, los mensajes permanecen seguros en su cola sin bloquear al productor ni a los otros consumidores.
* **Habilitación de patrones avanzados:** Permite la implementación limpia de CQRS (Separación de Responsabilidad de Consulta y Comando) y modelos de lectura optimizados.

### Negativas
* **Consistencia eventual:** Los datos en los servicios consumidores no se actualizan en el mismo milisegundo que en el productor; existe una ventana de latencia de propagación propia del modelo asíncrono.
* **Monitoreo distribuido indispensable:** La depuración de errores requiere correlacionar eventos a través de identificadores únicos (`id` de evento) en los registros de múltiples servicios.

## Alternativas consideradas

* **Orquestación síncrona HTTP directa:** Descartada. Convierte a `employees-service` en un cuello de botella propenso a fallos en cascada si cualquiera de los tres servicios secundarios experimenta lentitud o indisponibilidad.
* **Sondeo periódico (Polling) a la base de datos o endpoints:** Descartada. Genera tráfico innecesario en la red y sobrecarga en la base de datos de empleados mediante consultas constantes a intervalos regulares, además de introducir retrasos artificiales en la notificación de cambios.
