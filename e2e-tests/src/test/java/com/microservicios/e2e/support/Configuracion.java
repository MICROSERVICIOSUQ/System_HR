package com.microservicios.e2e.support;

/**
 * Configuración de la suite, tomada del entorno.
 *
 * <p>Nada está escrito a fuego: la URL base y las credenciales cambian entre la
 * máquina de un desarrollador, el contenedor de pruebas del Compose y el agente
 * de Jenkins. Si falta una variable se usa el valor de desarrollo, de modo que
 * {@code mvn test} funcione recién clonado el repositorio.
 */
public final class Configuracion {

    private Configuracion() {}

    /** URL del API Gateway: único punto de entrada del sistema. */
    public static final String BASE_URL = env("BASE_URL", "http://localhost:8080");

    /** Administrador semilla que crea el auth-service al arrancar. */
    public static final String ADMIN_USER = env(
            "ADMIN_USER", env("ADMIN_USUARIO", "admin@empresa.com"));
    public static final String ADMIN_PASSWORD = env("ADMIN_PASSWORD", "Admin12345");

    /**
     * Usuario regular usado como prefijo de correo para cuentas únicas creadas
     * durante cada escenario. También admite una dirección de correo como base.
     */
    public static final String REGULAR_USER = env("REGULAR_USER", "e2e");
    public static final String REGULAR_PASSWORD = env(
            "REGULAR_PASSWORD", env("PASSWORD_EMPLEADO", "Secreta123"));

    /**
     * Contraseña que la suite establece para los empleados que crea.
     * Cumple la política del auth-service: 8+ caracteres con letra y dígito.
     */
    public static final String PASSWORD_EMPLEADO = REGULAR_PASSWORD;

    // ── Parámetros de polling ────────────────────────────────────────────────
    // Justificación de los valores, medida sobre este sistema:
    //
    // La cadena asíncrona más larga del ecosistema es
    //   POST /empleados → empleado.creado → auth-service crea la cuenta →
    //   usuario.creado → notifications-service persiste la notificación
    //
    // En las ejecuciones observadas se completa en 200-600 ms. Con 15 intentos
    // cada 1 s se tolera un retraso de 15 s: unas 25 veces el tiempo normal, que
    // cubre el arranque en frío del broker y la lentitud de un agente de CI
    // cargado, sin que un fallo real tarde más de 15 s en delatarse.
    //
    // El polling termina en cuanto la condición se cumple, así que el caso normal
    // cuesta un solo intento. Una espera fija de 15 s pagaría todo ese tiempo siempre.

    public static final int POLL_MAX_INTENTOS =
            Integer.parseInt(env("POLL_MAX_INTENTOS", "15"));

    public static final long POLL_INTERVALO_MS =
            Long.parseLong(env("POLL_INTERVALO_MS", "1000"));

    private static String env(String clave, String porDefecto) {
        String valor = System.getenv(clave);
        if (valor == null || valor.isBlank()) {
            valor = System.getProperty(clave);
        }
        return (valor == null || valor.isBlank()) ? porDefecto : valor.trim();
    }
}
