# Decisiones de arquitectura

Registro de decisiones técnicas (ADR) del sistema. Cada entrada documenta el
contexto en que se tomó, la decisión, las alternativas descartadas y las
consecuencias que el equipo acepta.

| ADR | Título | Estado |
|---|---|---|
| [ADR-001](ADR-001-motor-de-base-de-datos.md) | Un solo motor (PostgreSQL) en instancias separadas por servicio | Aceptada |
| [ADR-002](ADR-002-estrategia-de-esquema.md) | Migraciones versionadas (Flyway y Prisma Migrate) para crear el esquema | Aceptada |
| [ADR-003](ADR-003-garantia-de-unicidad.md) | Unicidad en dos capas: consulta previa + restricción `UNIQUE` | Aceptada |

## Formato

```text
# ADR-NNN: Título

## Estado
## Contexto
## Decisión
## Alternativas consideradas
## Consecuencias
```

Una ADR no se edita cuando la decisión cambia: se crea una nueva que la
reemplaza y la anterior pasa a estado *Reemplazada por ADR-NNN*.
