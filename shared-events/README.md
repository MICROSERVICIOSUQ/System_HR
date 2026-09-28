# shared-events — Catálogo de Eventos System_HR

Módulo de contratos compartidos para la comunicación asíncrona del sistema System_HR.
No contiene código ejecutable. Solo define los contratos de mensajería que todos los
microservicios deben respetar.

## Estructura

```
shared-events/
├── catalog/                  # Ejemplos JSON de cada evento
│   ├── empleado-creado.json
│   ├── empleado-actualizado.json
│   ├── empleado-retirado.json
│   └── vacaciones-programadas.json
├── schemas/                  # JSON Schemas para validación
│   ├── event-envelope.schema.json
│   ├── empleado.schema.json
│   └── vacaciones.schema.json
└── docs/
    └── event-catalog.md      # Documentación detallada del catálogo
```

## Event Envelope

Todos los eventos siguen el mismo sobre estándar:

```json
{
  "id":         "<UUID v4 único por evento>",
  "type":       "<dominio.accion>",
  "version":    "1.0",
  "occurredAt": "<ISO 8601 UTC>",
  "producer":   "<nombre-del-servicio>",
  "data":       { }
}
```

## Eventos registrados

| Tipo | Productor | Schema de data |
|---|---|---|
| `empleado.creado` | employees-service | `empleado.schema.json` |
| `empleado.actualizado` | employees-service | `empleado.schema.json` |
| `empleado.retirado` | employees-service | `empleado.schema.json` |
| `vacaciones.programadas` | vacations-service | `vacaciones.schema.json` |

## Reglas de contrato

1. El campo `id` debe ser un UUID v4 generado por el productor en el momento del evento.
2. El campo `occurredAt` debe ser UTC en formato ISO 8601 (`2026-09-27T22:00:00.000Z`).
3. El campo `version` identifica la versión del contrato. Al cambiar campos de `data`, incrementar la versión.
4. El campo `data` no puede ser `null`. Si no hay datos adicionales, usar `{}`.
5. No agregar campos fuera del envelope estándar en el nivel raíz.
