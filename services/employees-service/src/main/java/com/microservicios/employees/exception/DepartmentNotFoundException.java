package com.microservicios.employees.exception;

public class DepartmentNotFoundException extends RuntimeException {

    public DepartmentNotFoundException(String departmentId) {
        super("El departamento con id " + departmentId + " no existe");
    }
}
