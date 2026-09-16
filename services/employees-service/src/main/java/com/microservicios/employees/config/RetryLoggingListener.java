package com.microservicios.employees.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

/**
 * Deja rastro en el log de cada intento fallido de las operaciones {@code @Retryable}.
 * {@code @EnableRetry} aplica automáticamente todo bean {@link RetryListener}, así que
 * el listener recibe también las excepciones de negocio (duplicados, no encontrado)
 * que no se reintentan; se filtran para registrar solo los fallos transitorios que
 * sí disparan reintentos.
 */
@Component
public class RetryLoggingListener implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(RetryLoggingListener.class);

    /** Mismas excepciones declaradas en {@code retryFor} del cliente HTTP y del servicio. */
    private static final List<Class<? extends Throwable>> TRANSITORIAS = List.of(
            ResourceAccessException.class,
            HttpServerErrorException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            QueryTimeoutException.class
    );

    @Override
    public <T, E extends Throwable> void onError(RetryContext context,
                                                 RetryCallback<T, E> callback,
                                                 Throwable throwable) {
        if (esTransitoria(throwable)) {
            log.warn("Intento {} fallido en {}: {} — {}",
                    context.getRetryCount(),
                    context.getAttribute(RetryContext.NAME),
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage());
        }
    }

    @Override
    public <T, E extends Throwable> void close(RetryContext context,
                                               RetryCallback<T, E> callback,
                                               Throwable throwable) {
        if (esTransitoria(throwable)) {
            log.error("Reintentos agotados en {} tras {} intentos",
                    context.getAttribute(RetryContext.NAME),
                    context.getRetryCount());
        }
    }

    private static boolean esTransitoria(Throwable t) {
        return t != null && TRANSITORIAS.stream().anyMatch(c -> c.isInstance(t));
    }
}
