CREATE TABLE IF NOT EXISTS "eventos_procesados" (
    "id"        TEXT         NOT NULL,
    "timestamp" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "eventos_procesados_pkey" PRIMARY KEY ("id")
);
