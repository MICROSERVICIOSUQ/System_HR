package com.microservicios.employees.controller;

import com.microservicios.employees.client.DepartmentClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CircuitBreakerController {
    private final CircuitBreakerRegistry registry;

    public CircuitBreakerController(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/empleados/circuit-breaker")
    public Map<String, Object> state() {
        var circuit = registry.circuitBreaker(DepartmentClient.CIRCUIT_NAME);
        var metrics = circuit.getMetrics();
        return Map.of("name", circuit.getName(), "state", circuit.getState().name(),
                "failedCalls", metrics.getNumberOfFailedCalls(),
                "successfulCalls", metrics.getNumberOfSuccessfulCalls(),
                "notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
    }
}
