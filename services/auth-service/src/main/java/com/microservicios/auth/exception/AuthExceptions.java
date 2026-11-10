package com.microservicios.auth.exception;

/**
 * Excepciones de dominio del auth-service, agrupadas porque cada una existe solo
 * para fijar el código HTTP con el que el {@link GlobalExceptionHandler} responde.
 */
public final class AuthExceptions {

    private AuthExceptions() {}

    /**
     * 401 — credenciales incorrectas o cuenta que no puede autenticarse.
     *
     * <p>El mensaje es deliberadamente genérico: distinguir "usuario no existe" de
     * "contraseña incorrecta" permitiría enumerar cuentas válidas.
     */
    public static class CredencialesInvalidasException extends RuntimeException {
        public CredencialesInvalidasException() {
            super("Credenciales inválidas");
        }
    }

    /**
     * 401 — la cuenta existe y la contraseña es correcta, pero su estado no permite
     * iniciar sesión (pendiente de activación, de vacaciones o retirada).
     */
    public static class CuentaNoHabilitadaException extends RuntimeException {
        public CuentaNoHabilitadaException(String detalle) {
            super(detalle);
        }
    }

    /** 403 — una cuenta suspendida o desactivada no puede restablecer su clave. */
    public static class CuentaNoPuedeRestablecerPasswordException extends RuntimeException {
        public CuentaNoPuedeRestablecerPasswordException(String detalle) {
            super(detalle);
        }
    }

    /** 400 — la nueva contraseña no cumple la política de seguridad. */
    public static class PasswordDebilException extends RuntimeException {
        public PasswordDebilException(String detalle) {
            super(detalle);
        }
    }

    /** 404 — no existe cuenta para el identificador o email indicado. */
    public static class CuentaNoEncontradaException extends RuntimeException {
        public CuentaNoEncontradaException(String referencia) {
            super("No existe una cuenta asociada a " + referencia);
        }
    }

    /** 401 — falta la identidad que el api-gateway debía propagar. */
    public static class IdentidadAusenteException extends RuntimeException {
        public IdentidadAusenteException() {
            super("La petición no llegó autenticada");
        }
    }
}
