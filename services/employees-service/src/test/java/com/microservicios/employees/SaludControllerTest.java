package com.microservicios.employees;

import com.microservicios.employees.controller.SaludController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SaludControllerTest {

    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
    private MockMvc mockMvc;

    @BeforeEach
    void configurarControlador() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SaludController(healthEndpoint, "employees-service"))
                .build();
    }

    @Test
    void healthDevuelve200CuandoLasDependenciasEstanActivas() throws Exception {
        when(healthEndpoint.health()).thenReturn(
                Health.up().withDetail("database", "UP").withDetail("messageBroker", "UP").build());

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("employees-service"));
    }

    @Test
    void healthDevuelve503CuandoUnaDependenciaEstaCaida() throws Exception {
        when(healthEndpoint.health()).thenReturn(
                Health.down().withDetail("database", "DOWN").withDetail("messageBroker", "UP").build());

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.service").value("employees-service"));
    }
}
