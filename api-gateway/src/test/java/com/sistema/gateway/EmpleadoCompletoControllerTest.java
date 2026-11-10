package com.sistema.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class EmpleadoCompletoControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void conservaEmpleadoYAdvierteCuandoPerfilNoEstaDisponible() throws Exception {
        EmpleadoCompletoClient client = mock(EmpleadoCompletoClient.class);
        Tracer tracer = mock(Tracer.class);
        JsonNode employee = objectMapper.readTree("{\"id\":\"E1\"}");
        JsonNode vacations = objectMapper.readTree("{\"saldo\":10}");
        when(client.getEmployee("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(employee));
        when(client.getProfile("E1", "unknown"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("profile down")));
        when(client.getVacations("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(vacations));

        ResponseEntity<?> response = new EmpleadoCompletoController(client, tracer)
                .getEmpleadoCompleto("E1")
                .join();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EmpleadoCompletoDTO result = (EmpleadoCompletoDTO) response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.empleado()).isEqualTo(employee);
        assertThat(result.perfil()).isNull();
        assertThat(result.vacaciones()).isEqualTo(vacations);
        assertThat(result.warnings())
                .containsExactly("Servicio de perfiles no disponible de forma temporal");
        assertThat(objectMapper.valueToTree(result).has("_warnings")).isTrue();
    }

    @Test
    void conservaEmpleadoYAdvierteCuandoVacacionesNoEstaDisponible() throws Exception {
        EmpleadoCompletoClient client = mock(EmpleadoCompletoClient.class);
        Tracer tracer = mock(Tracer.class);
        JsonNode employee = objectMapper.readTree("{\"id\":\"E1\"}");
        JsonNode profile = objectMapper.readTree("{\"ciudad\":\"Armenia\"}");
        when(client.getEmployee("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(employee));
        when(client.getProfile("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(profile));
        when(client.getVacations("E1", "unknown"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("vacations down")));

        ResponseEntity<?> response = new EmpleadoCompletoController(client, tracer)
                .getEmpleadoCompleto("E1")
                .join();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EmpleadoCompletoDTO result = (EmpleadoCompletoDTO) response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.empleado()).isEqualTo(employee);
        assertThat(result.perfil()).isEqualTo(profile);
        assertThat(result.vacaciones()).isNull();
        assertThat(result.warnings())
                .containsExactly("Servicio de vacaciones no disponible de forma temporal");
    }

    @Test
    void circuitBreakerAbiertoDevuelveEmpleadoParcialConAdvertencia() throws Exception {
        EmpleadoCompletoClient client = mock(EmpleadoCompletoClient.class);
        Tracer tracer = mock(Tracer.class);
        JsonNode employee = objectMapper.readTree("{\"id\":\"E1\"}");
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("profiles");
        breaker.transitionToOpenState();
        CallNotPermittedException circuitOpen = assertThrows(
                CallNotPermittedException.class,
                () -> breaker.executeSupplier(() -> "unavailable"));

        when(client.getEmployee("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(employee));
        when(client.getProfile("E1", "unknown")).thenThrow(circuitOpen);
        when(client.getVacations("E1", "unknown")).thenReturn(CompletableFuture.completedFuture(null));

        ResponseEntity<?> response = new EmpleadoCompletoController(client, tracer)
                .getEmpleadoCompleto("E1")
                .join();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EmpleadoCompletoDTO result = (EmpleadoCompletoDTO) response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.empleado()).isEqualTo(employee);
        assertThat(result.perfil()).isNull();
        assertThat(result.vacaciones()).isNull();
        assertThat(result.warnings())
                .containsExactly("Servicio de perfiles no disponible de forma temporal");
    }
}
