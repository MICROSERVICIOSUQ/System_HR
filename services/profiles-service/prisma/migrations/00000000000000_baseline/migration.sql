-- Migración baseline generada para profiles-service (Read Model CQRS)
-- Tabla: EmployeeProfile
-- Restricciones: employeeId UNIQUE, lastEventId UNIQUE (idempotencia)

CREATE TABLE IF NOT EXISTS "EmployeeProfile" (
    "id"             TEXT         NOT NULL,
    "employeeId"     TEXT         NOT NULL,
    "nombre"         TEXT         NOT NULL,
    "apellido"       TEXT         NOT NULL,
    "email"          TEXT         NOT NULL,
    "cargo"          TEXT         NOT NULL,
    "departamentoId" TEXT         NOT NULL,
    "fechaIngreso"   TIMESTAMP(3) NOT NULL,
    "fechaRetiro"    TIMESTAMP(3),
    "estado"         TEXT         NOT NULL DEFAULT 'ACTIVO',
    "lastEventId"    TEXT         NOT NULL,
    "updatedAt"      TIMESTAMP(3) NOT NULL,

    CONSTRAINT "EmployeeProfile_pkey" PRIMARY KEY ("id")
);

-- Índice UNIQUE para clave de negocio del empleado
CREATE UNIQUE INDEX IF NOT EXISTS "EmployeeProfile_employeeId_key"
    ON "EmployeeProfile"("employeeId");

-- Índice UNIQUE para idempotencia (UUID del último evento aplicado)
CREATE UNIQUE INDEX IF NOT EXISTS "EmployeeProfile_lastEventId_key"
    ON "EmployeeProfile"("lastEventId");
