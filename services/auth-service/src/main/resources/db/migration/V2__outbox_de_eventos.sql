CREATE TABLE outbox_evento (
    id VARCHAR(36) PRIMARY KEY,
    tipo VARCHAR(80) NOT NULL,
    agregado_id VARCHAR(60) NOT NULL,
    payload TEXT NOT NULL,
    traceparent VARCHAR(55),
    creado_en TIMESTAMP(3) NOT NULL,
    publicado_en TIMESTAMP(3),
    intentos INTEGER NOT NULL DEFAULT 0,
    ultimo_error VARCHAR(500)
);
CREATE INDEX idx_auth_outbox_pendientes ON outbox_evento (publicado_en, creado_en);
