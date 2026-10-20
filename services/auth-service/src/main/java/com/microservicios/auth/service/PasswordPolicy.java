package com.microservicios.auth.service;

import com.microservicios.auth.exception.AuthExceptions.PasswordDebilException;
import org.springframework.stereotype.Component;

/**
 * Política de contraseñas que exige el enunciado en {@code /auth/change-password}
 * y que se aplica también al establecimiento inicial.
 *
 * <p>Mínimo 8 caracteres, al menos una letra y al menos un dígito. Es una regla
 * deliberadamente simple y explícita: el objetivo del reto es que exista y se
 * valide en el servidor, no construir un verificador de entropía.
 */
@Component
public class PasswordPolicy {

    public static final int LONGITUD_MINIMA = 8;

    public void validar(String password) {
        if (password == null || password.length() < LONGITUD_MINIMA) {
            throw new PasswordDebilException(
                    "La contraseña debe tener al menos " + LONGITUD_MINIMA + " caracteres");
        }
        boolean tieneLetra = password.chars().anyMatch(Character::isLetter);
        boolean tieneDigito = password.chars().anyMatch(Character::isDigit);

        if (!tieneLetra || !tieneDigito) {
            throw new PasswordDebilException(
                    "La contraseña debe incluir al menos una letra y un dígito");
        }
    }
}
