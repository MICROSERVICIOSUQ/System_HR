package com.microservicios.employees.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DepartmentClient {
    public static final String CIRCUIT_NAME = "departmentsCircuitBreaker";
    private final DepartmentHttpClient httpClient;
    private final CircuitBreaker circuitBreaker;

    public DepartmentClient(DepartmentHttpClient httpClient, CircuitBreakerRegistry registry) {
        this.httpClient = httpClient;
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                LoggerFactory.getLogger(DepartmentClient.class).info("Circuito departamentos: {}",
                        event.getStateTransition()));
    }

    public boolean existsById(String departmentId) {
        // One exhausted retry sequence is one failed call. OPEN never enters the HTTP client.
        // A remote 404 returns false and counts as a successful communication.
        return circuitBreaker.executeSupplier(() -> httpClient.existsById(departmentId));
    }
}
