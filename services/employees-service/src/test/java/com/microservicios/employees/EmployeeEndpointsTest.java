package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmployeeEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DepartmentClient departmentClient;

    @BeforeEach
    void existingDepartment() {
        when(departmentClient.existsById("IT")).thenReturn(true);
    }

    private static final String JUAN = """
            {
              "id": "E001",
              "nombre": "Juan",
              "apellido": "Pérez",
              "email": "juan.perez@empresa.com",
              "numeroEmpleado": "EMP-2026-001",
              "cargo": "Desarrollador Senior",
              "area": "Tecnología",
              "departamentoId": "IT",
              "fechaIngreso": "2026-02-10",
              "estado": "ACTIVO"
            }
            """;

    @Test
    @Order(1)
        void successfulRegistrationPreservesClientId() throws Exception {
        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JUAN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("E001"))
                .andExpect(jsonPath("$.nombre").value("Juan"))
                .andExpect(jsonPath("$.apellido").value("Pérez"))
                .andExpect(jsonPath("$.numeroEmpleado").value("EMP-2026-001"))
                .andExpect(jsonPath("$.departamentoId").value("IT"))
                .andExpect(jsonPath("$.fechaIngreso").value("2026-02-10"))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    @Order(2)
        void findByClientId() throws Exception {
        mockMvc.perform(get("/empleados/E001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("E001"))
                .andExpect(jsonPath("$.email").value("juan.perez@empresa.com"));
    }

    @Test
    @Order(3)
        void duplicateIdReturns409() throws Exception {
        String mismoId = JUAN
                .replace("juan.perez@empresa.com", "otro.correo@empresa.com")
                .replace("EMP-2026-001", "EMP-2026-999");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismoId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("El id 'E001' ya está registrado"))
                .andExpect(jsonPath("$.path").value("/empleados"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @Order(4)
        void duplicateEmailReturns409() throws Exception {
        String otroIdMismoEmail = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E002\"")
                .replace("EMP-2026-001", "EMP-2026-999");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otroIdMismoEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("El email 'juan.perez@empresa.com' ya está registrado"));
    }

    @Test
    @Order(5)
        void duplicateEmployeeNumberReturns409() throws Exception {
        String otroIdOtroEmail = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E003\"")
                .replace("juan.perez@empresa.com", "pedro.lopez@empresa.com");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otroIdOtroEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("El número de empleado 'EMP-2026-001' ya está registrado"));
    }

    @Test
    @Order(6)
        void emptyBodyReturns400WithAllRequiredFields() throws Exception {
        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("La solicitud contiene campos inválidos"))
                .andExpect(jsonPath("$.errores", hasSize(9)))
                .andExpect(jsonPath("$.errores[*].campo").value(containsInAnyOrder(
                        "id", "nombre", "apellido", "email", "numeroEmpleado",
                        "cargo", "area", "departamentoId", "fechaIngreso")));
    }

    @Test
    @Order(7)
        void missingRequiredFieldReturns400WithThatField() throws Exception {
        String sinNombre = JUAN.replace("\"nombre\": \"Juan\",", "");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sinNombre))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores", hasSize(1)))
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("El nombre es obligatorio"));
    }

    @Test
    @Order(8)
        void invalidEmailFormatReturns400() throws Exception {
        String emailMalo = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E020\"")
                .replace("juan.perez@empresa.com", "esto-no-es-un-correo");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailMalo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("email"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("El email no tiene un formato válido"));
    }

    @Test
    @Order(9)
        void invalidStatusReturns400() throws Exception {
        String estadoMalo = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E021\"")
                .replace("\"estado\": \"ACTIVO\"", "\"estado\": \"JUBILADO\"");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(estadoMalo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("El cuerpo de la petición no es un JSON válido o contiene valores con formato incorrecto"));
    }

    @Test
    @Order(10)
        void unknownEmployeeReturns404() throws Exception {
        mockMvc.perform(get("/empleados/E999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("El empleado con id E999 no existe"))
                .andExpect(jsonPath("$.path").value("/empleados/E999"));
    }

    @Test
    @Order(11)
        void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/ruta-invalida"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Recurso no encontrado"));
    }

    @Test
    @Order(12)
    void unsupportedMethodReturns404() throws Exception {
        mockMvc.perform(put("/empleados"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Recurso no encontrado"));
    }

    @Test
    @Order(13)
    void listEmployeesReturns200() throws Exception {
        mockMvc.perform(get("/empleados"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("E001"));
    }

    @Test
    @Order(14)
    void unknownDepartmentReturns400() throws Exception {
        String departamentoInexistente = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E040\"")
                .replace("juan.perez@empresa.com", "departamento.inexistente@empresa.com")
                .replace("EMP-2026-001", "EMP-2026-040")
                .replace("\"departamentoId\": \"IT\"", "\"departamentoId\": \"NOPE\"");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departamentoInexistente))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("El departamento con id NOPE no existe"));
    }

    @Test
    @Order(15)
    void departmentServiceFailureReturns503() throws Exception {
        when(departmentClient.existsById("FAIL")).thenThrow(new ResourceAccessException("Connection timed out"));

        String departamentoFalla = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E050\"")
                .replace("juan.perez@empresa.com", "departamento.falla@empresa.com")
                .replace("EMP-2026-001", "EMP-2026-050")
                .replace("\"departamentoId\": \"IT\"", "\"departamentoId\": \"FAIL\"");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(departamentoFalla))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message")
                        .value("El servicio de departamentos no está disponible temporalmente"));
    }

    @Test
    @Order(16)
    void openApiDocsEndpointReturns200WithSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Microservicio de Empleados"))
                .andExpect(jsonPath("$.paths['/empleados']").exists());
    }

    @Test
    @Order(17)
    void swaggerUiEndpointReturnsRedirect() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @Order(18)
    void registrationWithoutEstadoDefaultsToActive() throws Exception {
        String sinEstado = """
                {
                  "id": "E099",
                  "nombre": "Carlos",
                  "apellido": "Gómez",
                  "email": "carlos.gomez@empresa.com",
                  "numeroEmpleado": "EMP-2026-099",
                  "cargo": "Desarrollador",
                  "area": "Tecnología",
                  "departamentoId": "IT",
                  "fechaIngreso": "2026-02-15"
                }
                """;

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sinEstado))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("E099"))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }
}