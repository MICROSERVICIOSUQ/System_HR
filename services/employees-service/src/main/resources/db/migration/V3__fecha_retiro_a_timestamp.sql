-- Migración V3: fecha_retiro pasa de DATE a TIMESTAMP(3).
--
-- El retiro de un empleado es un hecho con hora, y esa hora importa para una
-- auditoría laboral: con precisión de día, dos retiros del mismo día quedaban
-- indistinguibles y se perdía el orden en que ocurrieron.
--
-- El cambio es ampliador, no destructivo: DATE -> TIMESTAMP conserva todos los
-- valores existentes y les asigna las 00:00:00 del día que ya tenían. Ningún
-- registro se pierde ni cambia de día. PostgreSQL sabe hacer esta conversión,
-- pero exige el USING explícito para confirmar que es intencional.
--
-- El tipo queda alineado con profiles-service, que ya almacenaba fechaRetiro
-- como TIMESTAMP(3) en su propio esquema. Antes de esta migración, el evento
-- empleado.retirado viajaba con una fecha sin hora que el consumidor ampliaba a
-- medianoche; ahora los dos lados coinciden.

ALTER TABLE employees
    ALTER COLUMN fecha_retiro TYPE TIMESTAMP(3)
        USING fecha_retiro::timestamp;

COMMENT ON COLUMN employees.fecha_retiro IS
    'Fecha y hora del retiro lógico (ISO-8601). NULL mientras el empleado está ACTIVO.';
