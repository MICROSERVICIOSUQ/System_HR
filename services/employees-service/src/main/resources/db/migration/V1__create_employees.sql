CREATE TABLE IF NOT EXISTS employees (
    id VARCHAR(255) PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL,
    apellido VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    employee_number VARCHAR(255) NOT NULL UNIQUE,
    cargo VARCHAR(255) NOT NULL,
    area VARCHAR(255) NOT NULL,
    departamento_id VARCHAR(255) NOT NULL,
    fecha_ingreso DATE NOT NULL,
    estado VARCHAR(50) NOT NULL
);