-- Migración V2: agrega columna fecha_retiro a la tabla employees.
-- Esta columna se rellena automáticamente cuando el estado cambia a RETIRADO.
-- Permite consultar el endpoint de auditoría filtrando por rango de fechas de retiro.
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS fecha_retiro DATE;
