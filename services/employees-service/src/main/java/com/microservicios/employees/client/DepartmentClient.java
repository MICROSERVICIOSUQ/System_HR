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
public class DepartmentClient {

    private final RestClient restClient;

    public DepartmentClient(@Value("${departments.service.url}") String departmentsServiceUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);

        this.restClient = RestClient.builder()
                .baseUrl(departmentsServiceUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttempts = 3,
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
