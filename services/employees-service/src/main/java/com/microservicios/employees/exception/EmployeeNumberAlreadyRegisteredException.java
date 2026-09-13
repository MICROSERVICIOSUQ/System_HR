package com.microservicios.employees.exception;

public class EmployeeNumberAlreadyRegisteredException extends RuntimeException {
    public EmployeeNumberAlreadyRegisteredException(String employeeNumber) {
        super("El número de empleado '" + employeeNumber + "' ya está registrado");
    }
}
