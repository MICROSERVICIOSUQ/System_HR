-- Outbox transaccional de eventos (remediación de la auditoría).
--
-- Antes, el alta de un empleado guardaba la fila y después publicaba el evento
-- en RabbitMQ dentro de un try/catch que solo registraba el fallo. Con el broker
-- caído el empleado quedaba creado y 'empleado.creado' no llegaba nunca: ni
-- cuenta en auth, ni perfil, ni saldo de vacaciones, y nadie lo reintentaba.
--
-- Ahora el evento se escribe aquí en la MISMA transacción que el cambio de
-- negocio —misma base de datos, así que no hace falta una transacción
-- distribuida— y un publicador periódico vacía la tabla. Si el broker no está,
-- las filas esperan.

CREATE TABLE IF NOT EXISTS outbox_evento (
    -- El id del sobre del evento, que es también la clave de idempotencia con
    -- la que los consumidores descartan los repetidos (ADR-007). Como clave
    -- primaria, impide además guardar dos veces el mismo evento.
    id            VARCHAR(36)  PRIMARY KEY,
    tipo          VARCHAR(80)  NOT NULL,
    agregado_id   VARCHAR(60)  NOT NULL,
    payload       TEXT         NOT NULL,
    creado_en     TIMESTAMP(3) NOT NULL,
    publicado_en  TIMESTAMP(3),
    intentos      INTEGER      NOT NULL DEFAULT 0,
    ultimo_error  VARCHAR(500)
);

-- El publicador solo consulta lo pendiente, ordenado por antigüedad:
--   WHERE publicado_en IS NULL ORDER BY creado_en
-- El índice compuesto con publicado_en primero sirve exactamente a esa consulta.
--
-- Lo natural en PostgreSQL seria un indice parcial —(creado_en) WHERE
-- publicado_en IS NULL— que no indexa los ya publicados, que con el tiempo son
-- la mayoria. No se usa porque las pruebas corren sobre H2, que no admite la
-- clausula WHERE en CREATE INDEX, y la migracion fallaba al arrancar el contexto
-- de Spring. Un esquema que solo funciona en produccion no se puede probar, y
-- esa es la peor de las dos ineficiencias.
CREATE INDEX IF NOT EXISTS idx_outbox_pendientes
    ON outbox_evento (publicado_en, creado_en);

-- Para rastrear los eventos de un empleado concreto cuando algo no cuadra.
CREATE INDEX IF NOT EXISTS idx_outbox_agregado
    ON outbox_evento (agregado_id);
