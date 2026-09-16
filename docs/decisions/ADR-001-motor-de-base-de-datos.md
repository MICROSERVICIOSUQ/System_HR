# ADR-001: Un solo motor (PostgreSQL) en instancias separadas por servicio

## Estado

Aceptada — 2026-09-15

## Contexto

Cada microservicio debe ser dueño exclusivo de sus datos: ningún servicio puede
leer ni escribir las tablas de otro. Esto obliga a que `employees-service` y
`departments-service` tengan bases de datos independientes.

Queda abierta la pregunta de **qué motor** usar en cada una. La arquitectura de
microservicios permite persistencia políglota: elegir para cada servicio el motor
que mejor se ajuste a su patrón de acceso. Para departamentos —un catálogo pequeño,
de lectura frecuente y estructura plana— un almacén de documentos o clave-valor
sería técnicamente defendible.

Sin embargo, el equipo es de dos personas con experiencia previa en SQL, sin
infraestructura de operación (backups, monitoreo, alertas) y con un calendario
de entregas cortas.

## Decisión

Ambos servicios usan **PostgreSQL 16**, cada uno en su **propio contenedor**, con
su **propio volumen** y **sus propias credenciales**:

| Servicio | Contenedor | Base de datos | Volumen |
|---|---|---|---|
| `departments-service` | `database-departamentos` | `gestion_departamentos` | `gestion-departamentos_postgres_data` |
| `employees-service` | `database-empleados` | `gestion_empleados` | `gestion-empleados_postgres_data` |

La separación es a nivel de instancia, no solo de esquema: no comparten proceso,
puerto ni volumen. Un servicio puede migrar su base a otro motor en el futuro sin
afectar al otro.

## Alternativas consideradas

**Persistencia políglota (PostgreSQL para empleados, MongoDB para departamentos).**
Descartada. La ventaja real —modelar departamentos como documentos— es marginal para
tres campos planos, y el costo es concreto: dos motores que aprender, dos
herramientas de migración con semánticas distintas, dos estrategias de backup, dos
imágenes que mantener y el doble de superficie para fallos de arranque.

**Una sola instancia de PostgreSQL con dos esquemas o dos bases lógicas.**
Descartada. Reduce contenedores, pero rompe el aislamiento: una caída o una
migración fallida en una base afectaría a la otra, y un `docker compose down -v`
borraría los datos de ambos servicios a la vez. También permite, por descuido, que
un servicio consulte tablas ajenas con las mismas credenciales.

**Base de datos embebida (H2, SQLite).**
Descartada para ejecución. H2 se conserva únicamente como base en memoria para los
tests de `employees-service`, donde su velocidad y ausencia de infraestructura son
una ventaja y la fidelidad al motor de producción no es crítica.

## Consecuencias

**Positivas**
- Una sola herramienta de backup, monitoreo y diagnóstico (`pg_isready`, `psql`).
- El healthcheck de Compose es idéntico para ambas bases.
- Cualquier miembro del equipo puede depurar cualquiera de las dos bases.
- Las restricciones `UNIQUE` y las transacciones se comportan igual en ambos servicios.

**Negativas / asumidas**
- Se renuncia a optimizar el motor por caso de uso. Si departamentos creciera hacia
  documentos con estructura variable, PostgreSQL seguiría sirviendo (`jsonb`), pero
  no sería la elección ideal.
- Dos contenedores de PostgreSQL consumen más memoria que uno con dos bases.
- Cada servicio arrastra su propio driver y su propia herramienta de migraciones
  (Flyway en Java, Prisma Migrate en Node); ver [ADR-002](ADR-002-estrategia-de-esquema.md).
