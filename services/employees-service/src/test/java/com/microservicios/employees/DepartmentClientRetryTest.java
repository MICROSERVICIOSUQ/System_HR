package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica el contrato de resiliencia documentado en el README:
 * 4 intentos (1 inicial + 3 reintentos) con esperas de 1 s → 2 s → 4 s.
 *
 * Apunta el cliente a un puerto cerrado para provocar ResourceAccessException
 * de forma inmediata, sin depender de departments-service.
 */
@SpringBootTest(properties = "departments.service.url=http://127.0.0.1:1")
class DepartmentClientRetryTest {

    @TestConfiguration
    static class ContadorDeIntentos {
        static final AtomicInteger errores = new AtomicInteger();

        @Bean
        RetryListener contador() {
            return new RetryListener() {
                @Override
                public <T, E extends Throwable> void onError(RetryContext ctx,
                                                             RetryCallback<T, E> cb,
                                                             Throwable t) {
                    errores.incrementAndGet();
                }
            };
        }
    }

    @Autowired
    private DepartmentClient client;

    @Test
    void agotaCuatroIntentosConEsperasDeUnoDosYCuatroSegundos() {
        ContadorDeIntentos.errores.set(0);
        Instant inicio = Instant.now();

        assertThatThrownBy(() -> client.existsById("IT"))
                .isInstanceOf(ResourceAccessException.class);

        Duration transcurrido = Duration.between(inicio, Instant.now());

        assertThat(ContadorDeIntentos.errores.get())
                .as("intentos fallidos = 1 inicial + 3 reintentos")
                .isEqualTo(4);

        assertThat(transcurrido)
                .as("esperas acumuladas 1s + 2s + 4s = 7s como mínimo")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(7));

        assertThat(transcurrido)
                .as("no debe haber una cuarta espera (8s) ni timeouts de conexión inflando el total")
                .isLessThan(Duration.ofSeconds(12));
    }
}
