-- Migración baseline generada para vacations-service (Consumidor asíncrono)
-- Tabla: VacationBalance
-- Restricciones: employeeId UNIQUE, lastEventId UNIQUE (idempotencia)

CREATE TABLE IF NOT EXISTS "VacationBalance" (
    "id"              TEXT         NOT NULL,
    "employeeId"      TEXT         NOT NULL,
    "nombre"          TEXT         NOT NULL,
    "apellido"        TEXT         NOT NULL,
    "email"           TEXT         NOT NULL,
    "departamentoId"  TEXT         NOT NULL,
    "diasDisponibles" INTEGER      NOT NULL DEFAULT 15,
    "diasTomados"     INTEGER      NOT NULL DEFAULT 0,
    "estadoEmpleado"  TEXT         NOT NULL DEFAULT 'ACTIVO',
    "lastEventId"     TEXT         NOT NULL,
    "updatedAt"       TIMESTAMP(3) NOT NULL,

    CONSTRAINT "VacationBalance_pkey" PRIMARY KEY ("id")
);

-- Índice UNIQUE para clave de negocio del empleado
CREATE UNIQUE INDEX IF NOT EXISTS "VacationBalance_employeeId_key"
    ON "VacationBalance"("employeeId");

-- Índice UNIQUE para idempotencia (UUID del último evento aplicado)
CREATE UNIQUE INDEX IF NOT EXISTS "VacationBalance_lastEventId_key"
    ON "VacationBalance"("lastEventId");
