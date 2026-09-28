# Catálogo de Eventos — System_HR

Referencia técnica completa de todos los eventos de dominio que circulan por el bus de mensajería del sistema.

---

## Índice

1. [Eventos del dominio Empleado](#eventos-del-dominio-empleado)
2. [Eventos del dominio Vacaciones](#eventos-del-dominio-vacaciones)
3. [Matriz de consumidores](#matriz-de-consumidores)

---

## Eventos del dominio Empleado

### `empleado.creado`

Se publica cuando un empleado es registrado con éxito en el sistema.

- **Productor:** `employees-service`
- **Exchange:** `hr.events` (fanout)
- **Versión:** `1.0`

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "type": "empleado.creado",
  "version": "1.0",
  "occurredAt": "2026-09-27T22:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "nombre": "Laura",
    "apellido": "Martínez",
    "email": "laura.martinez@empresa.com",
    "departamentoId": "3c4d5e6f-7a8b-9c0d-e1f2-a3b4c5d6e7f8",
    "cargo": "Ingeniería de Software",
    "fechaIngreso": "2026-09-27",
    "fechaRetiro": null
  }
}
```

**Consumidores esperados:**

| Servicio | Cola | Acción |
|---|---|---|
| profiles-service | `profiles.queue` | Crear perfil inicial del empleado |
| notifications-service | `notifications.queue` | Enviar correo de bienvenida |
| vacations-service | `vacations.queue` | Inicializar saldo de días de vacaciones |

---

### `empleado.actualizado`

Se publica cuando los datos de un empleado son modificados.

- **Productor:** `employees-service`
- **Exchange:** `hr.events` (fanout)
- **Versión:** `1.0`

```json
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f01234567891",
  "type": "empleado.actualizado",
  "version": "1.0",
  "occurredAt": "2026-09-27T22:30:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "nombre": "Laura",
    "apellido": "Martínez Rojas",
    "email": "laura.martinez@empresa.com",
    "departamentoId": "9a0b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d",
    "cargo": "Ingeniería de Software Senior",
    "fechaIngreso": "2026-09-27",
    "fechaRetiro": null
  }
}
```

**Consumidores esperados:**

| Servicio | Cola | Acción |
|---|---|---|
| profiles-service | `profiles.queue` | Actualizar datos del perfil |
| notifications-service | `notifications.queue` | Notificar cambio de cargo o departamento |

---

### `empleado.retirado`

Se publica cuando un empleado es retirado de la organización.

- **Productor:** `employees-service`
- **Exchange:** `hr.events` (fanout)
- **Versión:** `1.0`

```json
{
  "id": "c3d4e5f6-a7b8-9012-cdef-012345678902",
  "type": "empleado.retirado",
  "version": "1.0",
  "occurredAt": "2026-09-27T23:00:00.000Z",
  "producer": "employees-service",
  "data": {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "nombre": "Laura",
    "apellido": "Martínez Rojas",
    "email": "laura.martinez@empresa.com",
    "departamentoId": "9a0b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d",
    "cargo": "Ingeniería de Software Senior",
    "fechaIngreso": "2026-09-27",
    "fechaRetiro": "2026-09-27"
  }
}
```

**Consumidores esperados:**

| Servicio | Cola | Acción |
|---|---|---|
| profiles-service | `profiles.queue` | Desactivar perfil |
| notifications-service | `notifications.queue` | Enviar correo de offboarding |
| vacations-service | `vacations.queue` | Cancelar vacaciones pendientes |

---

## Eventos del dominio Vacaciones

### `vacaciones.programadas`

Se publica cuando se registra un período de vacaciones para un empleado.

- **Productor:** `vacations-service`
- **Exchange:** `hr.events` (fanout)
- **Versión:** `1.0`

```json
{
  "id": "d4e5f6a7-b8c9-0123-defa-123456789013",
  "type": "vacaciones.programadas",
  "version": "1.0",
  "occurredAt": "2026-09-27T23:30:00.000Z",
  "producer": "vacations-service",
  "data": {
    "id": "e5f6a7b8-c9d0-1234-efab-234567890124",
    "empleadoId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "fechaInicio": "2026-10-15",
    "fechaFin": "2026-10-24",
    "diasHabiles": 8,
    "estado": "PENDIENTE",
    "observacion": null
  }
}
```

**Consumidores esperados:**

| Servicio | Cola | Acción |
|---|---|---|
| notifications-service | `notifications.queue` | Notificar al empleado y al área de RRHH |
| profiles-service | `profiles.queue` | Registrar ausencia en el perfil |

---

## Matriz de consumidores

| Evento | profiles.queue | notifications.queue | vacations.queue |
|---|:---:|:---:|:---:|
| `empleado.creado` | ✅ | ✅ | ✅ |
| `empleado.actualizado` | ✅ | ✅ | — |
| `empleado.retirado` | ✅ | ✅ | ✅ |
| `vacaciones.programadas` | ✅ | ✅ | — |
