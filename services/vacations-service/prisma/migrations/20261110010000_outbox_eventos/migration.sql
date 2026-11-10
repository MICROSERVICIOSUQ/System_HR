CREATE TABLE "outbox_evento" (
    "id" VARCHAR(36) PRIMARY KEY,
    "secuencia" BIGSERIAL NOT NULL UNIQUE,
    "tipo" VARCHAR(80) NOT NULL,
    "agregado_id" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "headers" JSONB NOT NULL,
    "creado_en" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "publicado_en" TIMESTAMP(3),
    "intentos" INTEGER NOT NULL DEFAULT 0,
    "ultimo_error" VARCHAR(500)
);
CREATE INDEX "outbox_evento_publicado_en_secuencia_idx" ON "outbox_evento" ("publicado_en", "secuencia");
