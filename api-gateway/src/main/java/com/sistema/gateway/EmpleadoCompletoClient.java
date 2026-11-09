package com.sistema.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class EmpleadoCompletoClient {

    private static final long TIMEOUT_MILLIS = 2_000;
    private static final Logger LOG = LoggerFactory.getLogger(EmpleadoCompletoClient.class);

    private final WebClient webClient;
    private final String employeesUrl;
    private final String profilesUrl;
    private final String vacationsUrl;

    public EmpleadoCompletoClient(
            WebClient.Builder webClientBuilder,
            @Value("${EMPLEADOS_URL:http://employees-service:8080}") String employeesUrl,
            @Value("${PERFILES_URL:http://profiles-service:8083}") String profilesUrl,
            @Value("${VACACIONES_URL:http://vacations-service:8084}") String vacationsUrl) {
        this.webClient = webClientBuilder.build();
        this.employeesUrl = employeesUrl;
        this.profilesUrl = profilesUrl;
        this.vacationsUrl = vacationsUrl;
    }

    @CircuitBreaker(name = "employeesService", fallbackMethod = "employeesFallback")
    public CompletableFuture<JsonNode> getEmployee(String id, String traceId) {
        return request(employeesUrl, "/empleados/{id}", id);
    }

    @CircuitBreaker(name = "profilesService", fallbackMethod = "profilesFallback")
    public CompletableFuture<JsonNode> getProfile(String id, String traceId) {
        return request(profilesUrl, "/perfiles/empleadoId/{id}", id);
    }

    @CircuitBreaker(name = "vacationsService", fallbackMethod = "vacationsFallback")
    public CompletableFuture<JsonNode> getVacations(String id, String traceId) {
        return request(vacationsUrl, "/vacaciones/empleadoId/{id}", id);
    }

    private CompletableFuture<JsonNode> request(
            String baseUrl, String path, String id) {
        return webClient.get()
                .uri(baseUrl + path, id)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .toFuture()
                .orTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<JsonNode> employeesFallback(
            String id, String traceId, Throwable error) {
        LOG.error("Empleado obligatorio no disponible id={} traceId={}", id, traceId, error);
        return CompletableFuture.failedFuture(error);
    }

    private CompletableFuture<JsonNode> profilesFallback(
            String id, String traceId, Throwable error) {
        LOG.warn("Fallback perfil=null para empleado id={} traceId={}", id, traceId, error);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<JsonNode> vacationsFallback(
            String id, String traceId, Throwable error) {
        LOG.warn("Fallback vacaciones=null para empleado id={} traceId={}", id, traceId, error);
        return CompletableFuture.completedFuture(null);
    }
}
