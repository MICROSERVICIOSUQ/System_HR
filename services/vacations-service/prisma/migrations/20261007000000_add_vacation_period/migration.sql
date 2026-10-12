-- Migración: Crear tabla VacationPeriod para períodos de vacaciones programadas
-- vacations-service ahora actúa también como PRODUCTOR vía POST /vacations

CREATE TABLE IF NOT EXISTS "VacationPeriod" (
    "id"            TEXT         NOT NULL,
    "empleadoId"    TEXT         NOT NULL,
    "fechaInicio"   DATE         NOT NULL,
    "fechaFin"      DATE         NOT NULL,
    "estado"        TEXT         NOT NULL DEFAULT 'PROGRAMADA',
    "fechaCreacion" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "VacationPeriod_pkey" PRIMARY KEY ("id")
);

-- Índice para consultas por empleado
CREATE INDEX IF NOT EXISTS "VacationPeriod_empleadoId_idx"
    ON "VacationPeriod"("empleadoId");

-- Índice para consultas por estado
CREATE INDEX IF NOT EXISTS "VacationPeriod_estado_idx"
    ON "VacationPeriod"("estado");
