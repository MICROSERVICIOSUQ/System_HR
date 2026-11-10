package com.sistema.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestController
public class EmpleadoCompletoController {

    private static final Logger LOG = LoggerFactory.getLogger(EmpleadoCompletoController.class);
    private final EmpleadoCompletoClient client;
    private final Tracer tracer;

    public EmpleadoCompletoController(
            EmpleadoCompletoClient client,
            Tracer tracer) {
        this.client = client;
        this.tracer = tracer;
    }

    @GetMapping("/empleados/{id}/completo")
    public CompletableFuture<ResponseEntity<?>> getEmpleadoCompleto(@PathVariable String id) {
        String traceId = traceId();
        LOG.info("Consultando empleado completo id={} traceId={}", id, traceId);

        CompletableFuture<JsonNode> empleado = client.getEmployee(id, traceId);
        CompletableFuture<OptionalResult> perfil = optionalResult(
                () -> client.getProfile(id, traceId),
                "Servicio de perfiles no disponible de forma temporal",
                id,
                traceId);
        CompletableFuture<OptionalResult> vacaciones = optionalResult(
                () -> client.getVacations(id, traceId),
                "Servicio de vacaciones no disponible de forma temporal",
                id,
                traceId);

        CompletableFuture<EmpleadoCompletoDTO> agregado = empleado
                .thenCombine(perfil, (employee, profile) ->
                        new PartialResult(employee, profile.value(), warnings(profile)))
                .thenCombine(vacaciones, (partial, vacation) ->
                        new EmpleadoCompletoDTO(
                                partial.empleado(),
                                partial.perfil(),
                                vacation.value(),
                                combineWarnings(partial.warnings(), warnings(vacation))));

        // El tipo del éxito se fija a ResponseEntity<?> explícitamente: sin eso,
        // thenApply infiere ResponseEntity<EmpleadoCompletoDTO> y el cuerpo de
        // error de exceptionally —un Map— ya no encaja, y el módulo no compila.
        return agregado
                .<ResponseEntity<?>>thenApply(dto -> ResponseEntity.ok(dto))
                .exceptionally(error -> {
                    LOG.error("No se pudo completar empleado id={} traceId={}", id, traceId, unwrap(error));
                    return errorResponse(unwrap(error), id, traceId);
                });
    }

    private ResponseEntity<?> errorResponse(Throwable error, String id, String traceId) {
        Throwable cause = unwrap(error);
        if (cause instanceof WebClientResponseException.NotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", 404,
                    "error", "Not Found",
                    "message", "Empleado no encontrado: " + id,
                    "traceId", traceId));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", 503,
                "error", "Service Unavailable",
                "message", "El servicio de empleados no está disponible temporalmente",
                "traceId", traceId));
    }

    private String traceId() {
        Span span = tracer.currentSpan();
        return span == null ? "unknown" : span.context().traceId();
    }

    private CompletableFuture<OptionalResult> optionalResult(
            Supplier<CompletableFuture<JsonNode>> request,
            String warning,
            String id,
            String traceId) {
        CompletableFuture<JsonNode> result;
        try {
            result = request.get();
        } catch (CallNotPermittedException error) {
            LOG.warn("Circuit breaker abierto para dependencia opcional id={} traceId={}", id, traceId);
            return CompletableFuture.completedFuture(new OptionalResult(null, warning));
        }
        return result.handle((value, error) -> {
            if (error == null) {
                return new OptionalResult(value, null);
            }
            LOG.warn("Dependencia opcional no disponible id={} traceId={}", id, traceId, unwrap(error));
            return new OptionalResult(null, warning);
        });
    }

    private List<String> warnings(OptionalResult result) {
        return result.warning() == null ? List.of() : List.of(result.warning());
    }

    private List<String> combineWarnings(List<String> first, List<String> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PartialResult(JsonNode empleado, JsonNode perfil, List<String> warnings) {
    }

    private record OptionalResult(JsonNode value, String warning) {
    }
}
