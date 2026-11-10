# Decisiones de Arquitectura (ADR)

Registro formal de decisiones técnicas (Architecture Decision Records) del sistema **System_HR**. Cada entrada documenta el contexto en que se tomó la decisión, la alternativa elegida, las opciones descartadas y las consecuencias técnicas asumidas por el equipo.

---

## Índice de Decisiones

| ADR | Título | Estado |
|---|---|---|
| [ADR-001](ADR-001-motor-de-base-de-datos.md) | Un solo motor (PostgreSQL) en instancias separadas por servicio | Aceptada |
| [ADR-002](ADR-002-estrategia-de-esquema.md) | Migraciones versionadas (Flyway y Prisma Migrate) para crear el esquema | Aceptada |
| [ADR-003](ADR-003-garantia-de-unicidad.md) | Unicidad en dos capas: consulta previa + restricción `UNIQUE` | Aceptada |
| [ADR-004](ADR-004-rabbitmq-message-broker.md) | Selección de RabbitMQ como Message Broker | Aceptada |
| [ADR-005](ADR-005-event-driven-architecture.md) | Adopción de una Arquitectura Orientada a Eventos | Aceptada |
| [ADR-006](ADR-006-fanout-exchange.md) | Uso de un Exchange Fanout para Eventos de Recursos Humanos | Aceptada |
| [ADR-007](ADR-007-idempotent-consumers.md) | Idempotencia en consumidores mediante Event ID | Aceptada |
| [ADR-008](ADR-008-validacion-jwt-en-el-gateway.md) | Validar el JWT en el API Gateway y no en cada microservicio | Aceptada |
| [ADR-009](ADR-009-estados-de-cuenta.md) | Estado de cuenta como enumeración, no como booleano | Aceptada |
| [ADR-010](ADR-010-piramide-de-pruebas.md) | Pirámide de pruebas con dobles en la base y Docker solo en la cima | Aceptada |
| [ADR-011](ADR-011-verificacion-de-efectos-asincronos.md) | Verificar efectos asíncronos con polling y disparar el tiempo a mano | Aceptada |
| [ADR-012](ADR-012-departments-en-python.md) | Reconstruir departments-service en Python (FastAPI) conservando su contrato | Aceptada |
| [ADR-013](ADR-013-renombrar-campos-publicados.md) | Renombrar un campo publicado conservando el nombre antiguo como alias | Aceptada |
| [ADR-014](ADR-014-outbox-transaccional.md) | Publicar los eventos mediante un outbox transaccional | Aceptada |

---

## Formato Estándar

Cada documento ADR se estructura bajo el siguiente formato:

```text
# ADR-NNN: Título

## Estado
## Contexto
## Decisión
## Justificación técnica (si aplica)
## Consecuencias (Positivas y Negativas)
## Alternativas consideradas
```

> **Nota:** Las decisiones arquitectónicas son inmutables. Si una decisión técnica cambia en el futuro, no se sobreescribe el documento existente; se redacta un nuevo ADR que reemplaza al anterior, marcando el previo con el estado *Reemplazada por ADR-NNN*.
