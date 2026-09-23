package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.repository.EmployeeRepository;
import com.microservicios.employees.service.EmployeeService;
import com.microservicios.employees.exception.EmailAlreadyRegisteredException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;

@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:circuit-tests;DB_CLOSE_DELAY=-1",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.sliding-window-size=3",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.minimum-number-of-calls=3",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.failure-rate-threshold=100",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.wait-duration-in-open-state=1s",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.permitted-number-of-calls-in-half-open-state=1",
    "resilience4j.circuitbreaker.instances.departmentsCircuitBreaker.automatic-transition-from-open-to-half-open-enabled=true"
})
@AutoConfigureMockMvc
class DepartmentCircuitBreakerTest {
    static AtomicInteger requests = new AtomicInteger();
    static AtomicInteger responseStatus = new AtomicInteger(503);
    static HttpServer server = startServer();
    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/departamentos", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(responseStatus.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void url(DynamicPropertyRegistry properties) {
        properties.add("departments.service.url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterAll static void stop() { server.stop(0); }
    @Autowired DepartmentClient client;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired MockMvc mvc;
    @Autowired EmployeeService service;
    @MockBean EmployeeRepository repository;
    CircuitBreaker circuit;
    @BeforeEach void resetCircuit() {
        circuit = registry.circuitBreaker(DepartmentClient.CIRCUIT_NAME);
        circuit.reset(); requests.set(0); responseStatus.set(503);
    }
    private String employeeBody(int id) {
        return """
            {"id":"CB-%d","nombre":"Ana","apellido":"Test","email":"cb%d@example.com",
             "numeroEmpleado":"CB-%d","cargo":"Dev","area":"TI",
             "departamentoId":"IT","fechaIngreso":"2026-09-22"}
            """.formatted(id,id,id);
    }
    @Test void opensSkipsNetworkAndRecoversWithoutRestart() throws Exception {
        for (int i=0; i<3; i++) {
            mvc.perform(post("/empleados").contentType(MediaType.APPLICATION_JSON).content(employeeBody(i)))
                    .andExpect(status().isServiceUnavailable());
        }
        assertThat(requests.get()).isEqualTo(12); // 3 operations, each with 4 attempts
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mvc.perform(post("/empleados").contentType(MediaType.APPLICATION_JSON).content(employeeBody(4)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El servicio de departamentos no está disponible temporalmente"));
        assertThat(requests.get()).isEqualTo(12);
        verify(repository, never()).save(any());
        mvc.perform(get("/empleados/circuit-breaker")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"));
        await().atMost(Duration.ofSeconds(3)).until(() -> circuit.getState() == CircuitBreaker.State.HALF_OPEN);
        responseStatus.set(404); // real response: unknown department, not a dependency failure
        mvc.perform(post("/empleados").contentType(MediaType.APPLICATION_JSON).content(employeeBody(5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El departamento con id IT no existe"));
        assertThat(requests.get()).isEqualTo(13);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void businessErrorsDoNotTripCircuit() {
        when(repository.existsByEmailIgnoreCase("duplicate@example.com")).thenReturn(true);
        var employee = new Employee(); employee.setEmail("duplicate@example.com");
        for (int i=0; i<5; i++) assertThatThrownBy(() -> service.register(employee))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        assertThat(requests.get()).isZero();
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void remote404IsSuccessfulCommunication() {
        responseStatus.set(404);
        for (int i=0; i<5; i++) assertThat(client.existsById("UNKNOWN")).isFalse();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }
    @Test void failedProbeReopensCircuit() {
        circuit.transitionToOpenState();
        await().atMost(Duration.ofSeconds(3)).until(() -> circuit.getState() == CircuitBreaker.State.HALF_OPEN);
        assertThatThrownBy(() -> client.existsById("IT"))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(requests.get()).isEqualTo(4);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
