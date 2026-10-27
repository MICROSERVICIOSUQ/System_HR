package com.microservicios.e2e.support;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Polling con reintentos para verificar efectos asíncronos.
 *
 * <p>En un sistema de eventos la consistencia es eventual: cuando un POST
 * devuelve 201, los demás servicios todavía no han procesado el evento. Consultar
 * de inmediato daría un falso negativo.
 *
 * <p><strong>Por qué polling y no una espera fija.</strong> Una espera fija
 * paga siempre su peor caso: si se espera 5 s y el evento tarda 200 ms, cada
 * escenario regala 4,8 s. Y si algún día tarda 5,1 s, la prueba falla de forma
 * intermitente. El polling termina en el primer intento cuando el sistema va
 * rápido, y tolera un retraso puntual sin volverse inestable.
 */
public final class Espera {

    private Espera() {}

    /**
     * Repite {@code condicion} hasta que devuelva {@code true} o se agoten los
     * intentos. Las excepciones de ejecución se tratan como "todavía no": un 404
     * mientras el evento viaja es un estado esperado, no un error.
     *
     * @param descripcion qué se estaba esperando, para el mensaje de fallo
     * @throws TimeoutException si la condición no se cumple dentro del plazo
     */
    public static void hasta(String descripcion, BooleanSupplier condicion)
            throws TimeoutException {
        hasta(descripcion, condicion,
                Configuracion.POLL_MAX_INTENTOS, Configuracion.POLL_INTERVALO_MS);
    }

    public static void hasta(String descripcion, BooleanSupplier condicion,
                             int maxIntentos, long intervaloMs) throws TimeoutException {
        validarParametros(maxIntentos, intervaloMs);
        RuntimeException ultimoError = null;

        for (int intento = 1; intento <= maxIntentos; intento++) {
            try {
                if (condicion.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException t) {
                ultimoError = t;
            }
            if (intento < maxIntentos) {
                esperarSiguienteIntento(intervaloMs);
            }
        }

        TimeoutException timeout = new TimeoutException(mensajeTimeout(
                "No se cumplió la condición esperada", descripcion, maxIntentos, intervaloMs));
        if (ultimoError != null) {
            timeout.initCause(ultimoError);
        }
        throw timeout;
    }

    /**
     * Igual que {@link #hasta}, pero devuelve el valor obtenido.
     *
     * <p>Se usa cuando el paso no solo comprueba que algo apareció, sino que
     * necesita el dato: el caso típico es el token de activación, que llega por
     * evento y hay que recuperar para continuar el flujo.
     */
    public static <T> T obtener(String descripcion, Supplier<T> proveedor)
            throws TimeoutException {
        return obtener(descripcion, proveedor,
                Configuracion.POLL_MAX_INTENTOS, Configuracion.POLL_INTERVALO_MS);
    }

    public static <T> T obtener(String descripcion, Supplier<T> proveedor,
                                int maxIntentos, long intervaloMs) throws TimeoutException {
        validarParametros(maxIntentos, intervaloMs);
        RuntimeException ultimoError = null;

        for (int intento = 1; intento <= maxIntentos; intento++) {
            try {
                T valor = proveedor.get();
                if (valor != null) {
                    return valor;
                }
            } catch (RuntimeException t) {
                ultimoError = t;
            }
            if (intento < maxIntentos) {
                esperarSiguienteIntento(intervaloMs);
            }
        }

        TimeoutException timeout = new TimeoutException(mensajeTimeout(
                "No se obtuvo el valor esperado", descripcion, maxIntentos, intervaloMs));
        if (ultimoError != null) {
            timeout.initCause(ultimoError);
        }
        throw timeout;
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
                                    int confirmaciones) throws TimeoutException {
        hasta(descripcion, condicion);

        for (int i = 0; i < confirmaciones; i++) {
            esperarSiguienteIntento(Configuracion.POLL_INTERVALO_MS);
            if (!condicion.getAsBoolean()) {
                throw new AssertionError(
                        "La condición se cumplió pero no se mantuvo (confirmación "
                                + (i + 1) + " de " + confirmaciones + "): " + descripcion);
            }
        }
    }

    private static void validarParametros(int maxIntentos, long intervaloMs) {
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("maxIntentos debe ser mayor que cero");
        }
        if (intervaloMs < 1) {
            throw new IllegalArgumentException("intervaloMs debe ser mayor que cero");
        }
    }

    private static String mensajeTimeout(String resultadoEsperado, String descripcion,
                                         int maxIntentos, long intervaloMs) {
        long totalSegundos = ((long) (maxIntentos - 1) * intervaloMs) / 1000;
        return String.format("%s tras %d intentos en ~%d s: %s",
                resultadoEsperado, maxIntentos, totalSegundos, descripcion);
    }

    private static void esperarSiguienteIntento(long intervaloMs) {
        LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(intervaloMs));
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Polling interrumpido");
        }
    }
}
