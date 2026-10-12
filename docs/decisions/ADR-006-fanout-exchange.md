# ADR-006: Uso de un Exchange Fanout para Eventos de Recursos Humanos

## Estado

Aceptada — 2026-09-28

## Contexto

En el dominio de Recursos Humanos, los eventos emitidos ante la creación, modificación o baja de un empleado poseen interés transversal. Tanto `notifications-service`, como `profiles-service` y `vacations-service` necesitan recibir una copia completa de cada evento emitido para ejecutar sus respectivos procesos de negocio.

RabbitMQ ofrece diferentes tipos de exchanges para la distribución de mensajes:
* **Direct:** Enruta según una coincidencia exacta de la *routing key*.
* **Topic:** Enruta según patrones comodín (`*`, `#`) en la *routing key*.
* **Fanout:** Difunde indiscriminadamente el mensaje a todas las colas enlazadas, ignorando la *routing key*.
* **Headers:** Enruta según atributos de cabecera en lugar de la clave de enrutamiento.

Se requería definir la topología de intercambio más eficiente, predecible y sencilla para este escenario de difusión 1 a N.

## Decisión

Configurar y utilizar un Exchange centralizado denominado **`hr.events`** de tipo **`fanout`**.

### Configuración Técnica

* **Nombre del Exchange:** `hr.events`
* **Tipo de Exchange:** `fanout`
* **Durable:** `true` (sobrevive a reinicios del broker)
* **Auto-delete:** `false`
* **Routing Key:** Cadena vacía (`""`), ignorada por la semántica fanout.

### Enlaces (Bindings) Declarados

El exchange replica cada mensaje automáticamente hacia las colas dedicadas de cada consumidor:

```text
                  [hr.events] (Exchange: fanout)
                       │
         ┌─────────────┼─────────────┐
         │ (broadcast) │ (broadcast) │ (broadcast)
         ▼             ▼             ▼
[notifications.queue] [profiles.queue] [vacations.queue]
```

## Consecuencias

### Positivas
* **Difusión simultánea y eficiente:** El broker duplica el puntero al mensaje en memoria y lo deposita de manera concurrente en cada cola sin costo de computación de filtros o expresiones regulares de cadenas.
* **Simplicidad de integración:** Los productores solo necesitan conocer el nombre del exchange (`hr.events`). No requieren coordinar complejas convenciones de *routing keys* con los consumidores.
* **Aislamiento de colas:** Cada microservicio dispone de su propia cola física con su propio ritmo de consumo y tamaño de buffer, sin interferir con las colas de los demás servicios.
* **Topología declarativa:** La infraestructura define el exchange y sus colas en el archivo de inicio `definitions.json`, garantizando consistencia inmediata en cualquier entorno.

### Negativas
* **Recepción total:** Cada consumidor recibe todos los eventos que transitan por el exchange. Si un consumidor solo estuviera interesado en un subconjunto de eventos, debe filtrar el mensaje por el campo `type` a nivel de aplicación (descarte silencioso con ACK).

## Alternativas consideradas

* **Direct Exchange con routing keys específicas (`empleado.creado`, etc.):** Descartada. Requeriría crear múltiples bindings por cada evento hacia cada una de las colas. Dado que en este reto todos los consumidores actuales requieren todos los eventos de empleados, añadía complejidad innecesaria sin aportar beneficio inmediato.
* **Topic Exchange con patrones (`empleado.*`):** Descartada. Es la evolución natural si en futuros retos se agregan eventos de otros dominios (ej. `departamento.*`, `vacaciones.*`) que solo interesen a consumidores específicos. Para el alcance actual, el exchange Fanout es la opción más limpia, rápida y directa.
