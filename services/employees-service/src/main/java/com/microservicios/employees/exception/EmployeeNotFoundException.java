package com.microservicios.employees.exception;

public class EmployeeNotFoundException extends RuntimeException {

    private final String id;

    public EmployeeNotFoundException(String id) {
        super("El empleado con id " + id + " no existe");
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
