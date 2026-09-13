package com.microservicios.employees.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("El email '" + email + "' ya está registrado");
    }
}
