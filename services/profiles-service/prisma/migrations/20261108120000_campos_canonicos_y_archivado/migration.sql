-- Perfil: cinco campos canónicos, renombrado de employeeId y marca de archivado.
--
-- Tres cambios, todos ampliadores: ninguna fila se pierde y ningún dato se
-- transforma de forma irreversible.
--
-- 1. employeeId -> empleadoId
--    El modelo canónico del sistema usa nombres en español (empleadoId,
--    departamentoId, fechaIngreso) y este campo era el único en inglés, lo que
--    obligaba a traducir en cada frontera. RENAME conserva los datos y el
--    índice UNIQUE; la respuesta de la API sigue exponiendo `employeeId` como
--    alias, así que ningún cliente se rompe.
--
-- 2. Los cinco campos del perfil, todos NULL-ables.
--    Son datos del perfil y no del empleado: employees-service no los conoce ni
--    los publica. Los rellena el propio empleado editando su perfil. Crearlos
--    NOT NULL habría exigido inventar valores para las filas existentes.
--
-- 3. archivado BOOLEAN DEFAULT false
--    Un perfil retirado no se borra —el historial se conserva— pero se archiva.
--    Las filas ya retiradas se marcan en esta misma migración para que el estado
--    quede coherente desde el primer momento.

-- ── 1. Renombrado de la clave de negocio ─────────────────────────────────────
-- DO + IF EXISTS para que la migración sea idempotente: si ya se aplicó, no
-- falla al reejecutarse.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'EmployeeProfile' AND column_name = 'employeeId'
    ) THEN
        ALTER TABLE "EmployeeProfile" RENAME COLUMN "employeeId" TO "empleadoId";
    END IF;
END $$;

-- El índice UNIQUE sobrevive al RENAME, pero con su nombre antiguo. Se renombra
-- también para que el esquema no quede con nombres contradictorios.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'EmployeeProfile_employeeId_key'
    ) THEN
        ALTER INDEX "EmployeeProfile_employeeId_key"
            RENAME TO "EmployeeProfile_empleadoId_key";
    END IF;
END $$;

-- ── 2. Los cinco campos canónicos del perfil ─────────────────────────────────
ALTER TABLE "EmployeeProfile"
    ADD COLUMN IF NOT EXISTS "telefono"      TEXT,
    ADD COLUMN IF NOT EXISTS "direccion"     TEXT,
    ADD COLUMN IF NOT EXISTS "ciudad"        TEXT,
    ADD COLUMN IF NOT EXISTS "biografia"     TEXT,
    ADD COLUMN IF NOT EXISTS "fechaCreacion" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ── 3. Marca de archivado ────────────────────────────────────────────────────
ALTER TABLE "EmployeeProfile"
    ADD COLUMN IF NOT EXISTS "archivado" BOOLEAN NOT NULL DEFAULT false;

-- Coherencia retroactiva: los perfiles ya retirados se archivan. Sin esto, un
-- filtro ?archivado=false devolvería perfiles de empleados que ya no están en la
-- empresa, que es exactamente lo que la marca viene a evitar.
UPDATE "EmployeeProfile"
SET "archivado" = true
WHERE "estado" = 'RETIRADO' AND "archivado" = false;

COMMENT ON COLUMN "EmployeeProfile"."archivado" IS
    'true cuando el empleado fue retirado. El perfil se conserva para auditoria, pero queda fuera de las consultas corrientes.';
COMMENT ON COLUMN "EmployeeProfile"."fechaCreacion" IS
    'Cuando se creo el perfil en el read model. Distinta de fechaIngreso, que es cuando entro el empleado a la empresa.';
