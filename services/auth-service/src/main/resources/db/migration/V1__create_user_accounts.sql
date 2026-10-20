-- Cuentas de acceso del sistema.
-- La clave primaria es el empleado_id porque es el valor que viaja en el claim
-- 'sub' del JWT y contra el que se compara la propiedad del recurso.
CREATE TABLE IF NOT EXISTS user_accounts (
    empleado_id   VARCHAR(255) PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    -- Hash BCrypt. NULL mientras la cuenta esté PENDIENTE_ACTIVACION:
    -- la cuenta existe pero todavía no tiene credencial utilizable.
    password_hash VARCHAR(255),
    role          VARCHAR(20)  NOT NULL,
    estado        VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_user_accounts_role
        CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT chk_user_accounts_estado
        CHECK (estado IN ('PENDIENTE_ACTIVACION', 'ACTIVA',
                          'SUSPENDIDA_TEMPORAL', 'DESACTIVADA_PERMANENTE'))
);

-- El login admite email o empleado_id; el índice acelera la vía del email.
CREATE INDEX IF NOT EXISTS idx_user_accounts_email ON user_accounts (LOWER(email));

-- Idempotencia de los consumidores: mismo patrón que los servicios del Reto 4.
-- Relevante aquí porque el scheduler de vacaciones puede emitir el mismo evento
-- más de una vez si el servicio se escala a N instancias.
CREATE TABLE IF NOT EXISTS eventos_procesados (
    id           VARCHAR(255) PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
