package com.microservicios.employees.exception;

public class EmployeeAlreadyRetiredException extends RuntimeException {

    public EmployeeAlreadyRetiredException(String id) {
        super("El empleado con id " + id + " ya está retirado");
    }
}
