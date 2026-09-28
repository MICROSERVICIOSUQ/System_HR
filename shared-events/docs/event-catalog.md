# Catálogo de Eventos — System_HR

Este documento especifica formalmente los eventos de dominio emitidos a través de RabbitMQ dentro del sistema de recursos humanos.

---

## Tabla de Eventos

| Evento | Versión | Productor | Descripción |
|---|---|---|---|
| empleado.creado | 1.0 | employees-service | Se registra un nuevo empleado |
| empleado.actualizado | 1.0 | employees-service | Se modifica información del empleado |
| empleado.retirado | 1.0 | employees-service | Retiro lógico del empleado |

---

## Event Envelope

Todos los eventos transmitidos por el exchange `hr.events` encapsulan su contenido dentro de un sobre estándar (*Event Envelope*), asegurando consistencia en la deserialización e idempotencia en los consumidores:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-28T20:00:00Z",
  "producer": "employees-service",
  "data": {
    "id": "E001",
    "nombre": "Juan",
    "apellido": "Perez",
    "email": "juan@empresa.com",
    "cargo": "Developer",
    "departamentoId": "D001"
  }
}
```

### Descripción de Atributos

* **`id`** → UUID único del evento. Utilizado por los microservicios consumidores como llave de idempotencia (`lastEventId`) para descartar eventos duplicados.
* **`type`** → Tipo o nombre del evento en formato `dominio.accion` (ej. `empleado.creado`).
* **`version`** → Versión semántica del contrato del evento (ej. `1.0`). Permite la evolución progresiva de los contratos.
* **`occurredAt`** → Marca temporal de emisión del evento en formato ISO 8601 UTC.
* **`producer`** → Identificador del microservicio emisor del evento (ej. `employees-service`).
* **`data`** → Objeto JSON con la información y atributos propios del negocio asociados a la acción ocurrida.
