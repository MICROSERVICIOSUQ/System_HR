# ADR-002: Migraciones versionadas (Flyway y Prisma Migrate) para crear el esquema

## Estado

Aceptada — 2026-09-15

## Contexto

Las tablas de cada base de datos deben existir antes de que el servicio atienda
la primera petición, y su creación debe ser **reproducible**: un tercero que clone
el repositorio y ejecute `docker compose up --build` tiene que obtener el mismo
esquema sin pasos manuales.

El esquema, además, va a evolucionar: nuevos estados de empleado, nuevas columnas,
nuevos índices. La estrategia elegida tiene que soportar esos cambios **sin
destruir los datos existentes** de un compañero que ya tenía el sistema levantado.

## Decisión

Cada servicio aplica **migraciones SQL versionadas** al arrancar, con la
herramienta nativa de su ecosistema:

| Servicio | Herramienta | Ubicación | Momento de aplicación |
|---|---|---|---|
| `employees-service` | Flyway | `src/main/resources/db/migration/V*.sql` | Al iniciar el contexto de Spring, antes de que Hibernate valide las entidades |
| `departments-service` | Prisma Migrate | `prisma/migrations/*/migration.sql` | `npx prisma migrate deploy` en el `CMD` del contenedor, antes de `tsx src/server.ts` |

En `employees-service`, Hibernate queda en `ddl-auto=validate`: **nunca** crea ni
altera tablas por su cuenta; solo comprueba que las entidades JPA coincidan con lo
que Flyway aplicó, y aborta el arranque si no coinciden.

## Alternativas consideradas

**Script `init.sql` montado en `/docker-entrypoint-initdb.d/`.**
Descartada. Es la opción más simple para la primera ejecución, pero PostgreSQL solo
lo ejecuta **cuando el volumen está vacío**. Cualquier cambio de esquema posterior
obliga a `docker compose down -v`, borrando todos los datos. En un equipo, eso
significa que cada cambio de esquema destruye los datos de prueba de todos.

**Auto-DDL del ORM (`ddl-auto=update`, `prisma db push`).**
Descartada. Cómodo al inicio, pero sin trazabilidad: no queda registro de qué cambió
ni cuándo, `update` no elimina columnas ni ajusta tipos de forma fiable, y un cambio
accidental en una entidad puede alterar una tabla con datos. En Prisma, `db push`
está documentado explícitamente como herramienta de prototipado, no de despliegue.

**Migraciones gestionadas fuera del servicio (contenedor `migrator` aparte).**
Descartada por ahora. Separa responsabilidades, pero añade un contenedor y una
dependencia más en Compose para un beneficio que solo aparece con varias réplicas
del mismo servicio arrancando a la vez.

## Consecuencias

**Positivas**
- Historial completo del esquema en el repositorio, revisable en un *pull request*.
- Idempotencia: cada herramienta lleva su tabla de control (`flyway_schema_history`,
  `_prisma_migrations`) y no reaplica lo ya aplicado.
- Evolución no destructiva: un `V2__...sql` o una nueva carpeta de migración altera
  la tabla existente conservando los registros.
- El esquema de `employees-service` incluye las restricciones `UNIQUE` desde `V1`;
  ver [ADR-003](ADR-003-garantia-de-unicidad.md).

**Negativas / asumidas**
- Dos herramientas con convenciones distintas (nombres de archivo, comandos).
- Cada cambio de modelo exige escribir la migración a mano (Flyway) o generarla y
  revisarla (Prisma). No hay "magia": es deliberado.
- Si una migración falla, el servicio no arranca. Es el comportamiento deseado
  —mejor un contenedor caído que uno sirviendo datos con un esquema a medias— pero
  exige leer los logs.
