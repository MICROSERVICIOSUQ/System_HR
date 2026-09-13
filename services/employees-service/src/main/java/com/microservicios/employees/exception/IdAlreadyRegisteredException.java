package com.microservicios.employees.exception;

public class IdAlreadyRegisteredException extends RuntimeException {
    public IdAlreadyRegisteredException(String id) {
        super("El id '" + id + "' ya está registrado");
    }
}
