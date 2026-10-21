package com.microservicios.e2e.support;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Polling con reintentos para verificar efectos asíncronos.
 *
 * <p>En un sistema de eventos la consistencia es eventual: cuando un POST
 * devuelve 201, los demás servicios todavía no han procesado el evento. Consultar
 * de inmediato daría un falso negativo.
 *
 * <p><strong>Por qué polling y no {@code Thread.sleep} fijo.</strong> Una espera
 * fija paga siempre su peor caso: si se espera 5 s y el evento tarda 200 ms, cada
 * escenario regala 4,8 s. Y si algún día tarda 5,1 s, la prueba falla de forma
 * intermitente. El polling termina en el primer intento cuando el sistema va
 * rápido, y tolera un retraso puntual sin volverse inestable.
 */
public final class Espera {

    private Espera() {}

    /**
     * Repite {@code condicion} hasta que devuelva {@code true} o se agoten los
     * intentos. Las excepciones se tratan como "todavía no": un 404 mientras el
     * evento viaja es un estado esperado, no un error.
     *
     * @param descripcion qué se estaba esperando, para el mensaje de fallo
     * @throws AssertionError si la condición no se cumple dentro del plazo
     */
    public static void hasta(String descripcion, BooleanSupplier condicion) {
        hasta(descripcion, condicion,
                Configuracion.POLL_MAX_INTENTOS, Configuracion.POLL_INTERVALO_MS);
    }

    public static void hasta(String descripcion, BooleanSupplier condicion,
                             int maxIntentos, long intervaloMs) {
        Throwable ultimoError = null;

        for (int intento = 1; intento <= maxIntentos; intento++) {
            try {
                if (condicion.getAsBoolean()) {
                    return;
                }
            } catch (Throwable t) {
                ultimoError = t;
            }
            if (intento < maxIntentos) {
                dormir(intervaloMs);
            }
        }

        long totalSegundos = (maxIntentos * intervaloMs) / 1000;
        String mensaje = String.format(
                "No se cumplió la condición esperada tras %d intentos en ~%d s: %s",
                maxIntentos, totalSegundos, descripcion);
        if (ultimoError != null) {
            throw new AssertionError(
                    mensaje + " | último error: " + ultimoError.getMessage(), ultimoError);
        }
        throw new AssertionError(mensaje);
    }

    /**
     * Igual que {@link #hasta}, pero devuelve el valor obtenido.
     *
     * <p>Se usa cuando el paso no solo comprueba que algo apareció, sino que
     * necesita el dato: el caso típico es el token de activación, que llega por
     * evento y hay que recuperar para continuar el flujo.
     */
    public static <T> T obtener(String descripcion, Supplier<T> proveedor) {
        return obtener(descripcion, proveedor,
                Configuracion.POLL_MAX_INTENTOS, Configuracion.POLL_INTERVALO_MS);
    }

    public static <T> T obtener(String descripcion, Supplier<T> proveedor,
                                int maxIntentos, long intervaloMs) {
        Throwable ultimoError = null;

        for (int intento = 1; intento <= maxIntentos; intento++) {
            try {
                T valor = proveedor.get();
                if (valor != null) {
                    return valor;
                }
            } catch (Throwable t) {
                ultimoError = t;
            }
            if (intento < maxIntentos) {
                dormir(intervaloMs);
            }
        }

        long totalSegundos = (maxIntentos * intervaloMs) / 1000;
        String mensaje = String.format(
                "No se obtuvo el valor esperado tras %d intentos en ~%d s: %s",
                maxIntentos, totalSegundos, descripcion);
        if (ultimoError != null) {
            throw new AssertionError(
                    mensaje + " | último error: " + ultimoError.getMessage(), ultimoError);
        }
        throw new AssertionError(mensaje);
    }

    /**
     * Comprueba que una condición se cumple y SIGUE cumpliéndose.
     *
     * <p>Verificar que "el empleado eventualmente no puede entrar" es distinto a
     * verificar que algo aparece: un login que falla ahora podría estar fallando
     * porque el evento aún no se procesó, o porque el sistema está roto. Este
     * método espera a que la condición se cumpla y luego la confirma varias veces
     * seguidas, de modo que un estado transitorio no se confunda con el definitivo.
     */
    public static void establemente(String descripcion, BooleanSupplier condicion,
                                    int confirmaciones) {
        hasta(descripcion, condicion);

        for (int i = 0; i < confirmaciones; i++) {
            dormir(Configuracion.POLL_INTERVALO_MS / 2);
            if (!condicion.getAsBoolean()) {
                throw new AssertionError(
                        "La condición se cumplió pero no se mantuvo (confirmación "
                                + (i + 1) + " de " + confirmaciones + "): " + descripcion);
            }
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Espera interrumpida", e);
        }
    }
}
