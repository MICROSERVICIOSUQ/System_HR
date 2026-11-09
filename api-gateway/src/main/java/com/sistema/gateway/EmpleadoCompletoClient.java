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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

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

    /**
     * Perfil del empleado.
     *
     * <p>La ruta es {@code /perfiles/{empleadoId}}: profiles-service monta su
     * router en {@code /perfiles} y resuelve el perfil con un único segmento
     * ({@code router.get('/:empleadoId')}). Express no hace coincidir un segmento
     * extra, así que {@code /perfiles/empleadoId/E601} devolvía 404 y el fallback
     * lo convertía en {@code perfil: null} en todas las respuestas.
     */
    @CircuitBreaker(name = "profilesService", fallbackMethod = "profilesFallback")
    public CompletableFuture<JsonNode> getProfile(String id, String traceId) {
        return requestOpcional(profilesUrl, "/perfiles/{id}", id);
    }

    /**
     * Saldo de vacaciones del empleado.
     *
     * <p>Se consulta el saldo y no la lista de períodos: es el recurso que
     * vacations-service identifica por empleado
     * ({@code /vacaciones/balances/{employeeId}}) y el que responde con un objeto,
     * acorde al campo {@code vacaciones} del DTO. Los períodos se listan con
     * {@code /vacaciones?empleadoId=}, que devuelve una colección.
     */
    @CircuitBreaker(name = "vacationsService", fallbackMethod = "vacationsFallback")
    public CompletableFuture<JsonNode> getVacations(String id, String traceId) {
        return requestOpcional(vacationsUrl, "/vacaciones/balances/{id}", id);
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

    /**
     * Igual que {@link #request}, pero un 404 se traduce en ausencia de dato.
     *
     * <p>Un empleado puede no tener perfil o saldo todavía: el read model se
     * construye por eventos y puede ir por detrás. Eso es una respuesta correcta
     * del servicio, no un fallo, y se resuelve antes de que el circuit breaker la
     * vea para no abrirlo por una comunicación exitosa. Es la misma distinción
     * que employees-service hace con el 404 de departamentos.
     */
    private CompletableFuture<JsonNode> requestOpcional(
            String baseUrl, String path, String id) {
        return webClient.get()
                .uri(baseUrl + path, id)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(WebClientResponseException.NotFound.class, ausente -> Mono.empty())
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
