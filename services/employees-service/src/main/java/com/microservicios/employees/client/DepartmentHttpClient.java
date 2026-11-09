package com.microservicios.employees.client;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class DepartmentHttpClient {

    private final RestClient restClient;

    /**
     * Construye el cliente con el registro de observación, y eso importa para la
     * trazabilidad distribuida (Reto 8).
     *
     * <p>Un {@code RestClient} creado con el estático {@code RestClient.builder()}
     * no lleva el interceptor de observación: el que abre un span de cliente y,
     * sobre todo, el que añade la cabecera {@code traceparent} a la petición
     * saliente. Sin él, la llamada a departments-service salía sin contexto de
     * traza y ese servicio aparecía en Zipkin como una traza suelta, desconectada
     * de la petición que la originó.
     *
     * <p>Se inyecta el {@link ObservationRegistry} y no el
     * {@code RestClient.Builder} auto-configurado, aunque este último también
     * resolvería el problema. El motivo es que su creación detecta el cliente
     * HTTP disponible en el entorno, y esa detección abre un socket: en un
     * entorno sin loopback disponible el bean falla y con él todo el contexto de
     * Spring, lo que tumbaba 25 pruebas. Pidiendo solo el registro se obtiene la
     * misma instrumentación sin arrastrar esa detección, y el
     * {@link SimpleClientHttpRequestFactory} con sus timeouts explícitos sigue
     * siendo el que decide cómo se hace la conexión.
     */
    public DepartmentHttpClient(@Value("${departments.service.url}") String departmentsServiceUrl,
                                ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);

        this.restClient = RestClient.builder()
                .baseUrl(departmentsServiceUrl)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 4 intentos en total = 1 llamada inicial + 3 reintentos, con esperas de
     * 1 s → 2 s → 4 s entre ellos. Agotados, la excepción sube al
     * GlobalExceptionHandler y se responde 503.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttempts = 4,
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public boolean existsById(String departmentId) {
        try {
            restClient.get()
                    .uri("/departamentos/{id}", departmentId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound exception) {
            return false;
        }
    }
}
