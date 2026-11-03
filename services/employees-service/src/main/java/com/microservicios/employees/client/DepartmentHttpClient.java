package com.microservicios.employees.client;

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
     * El {@code RestClient.Builder} llega inyectado, y eso importa para la
     * trazabilidad distribuida (Reto 8).
     *
     * <p>Antes se usaba el estático {@code RestClient.builder()}, que crea un
     * cliente limpio sin los interceptores que registra Spring Boot. Uno de
     * ellos es el de observación: el que abre un span de cliente y, sobre todo,
     * el que añade la cabecera {@code traceparent} a la petición saliente. Sin
     * él, la llamada a departments-service salía sin contexto de traza y ese
     * servicio aparecía en Zipkin como una traza suelta, desconectada de la
     * petición que la originó.
     *
     * <p>El builder que publica Spring Boot es de ámbito prototipo, así que
     * configurarlo aquí no afecta a nadie más.
     */
    public DepartmentHttpClient(@Value("${departments.service.url}") String departmentsServiceUrl,
                                RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);

        this.restClient = restClientBuilder
                .baseUrl(departmentsServiceUrl)
                .requestFactory(requestFactory)
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
