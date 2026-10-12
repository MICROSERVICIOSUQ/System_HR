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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    @MockBean
    private com.microservicios.employees.messaging.EmployeeEventPublisher eventPublisher;

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
        void duplicateIdReturns400() throws Exception {
        String mismoId = JUAN
                .replace("juan.perez@empresa.com", "otro.correo@empresa.com")
                .replace("EMP-2026-001", "EMP-2026-999");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismoId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("El id 'E001' ya está registrado"))
                .andExpect(jsonPath("$.path").value("/empleados"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @Order(4)
        void duplicateEmailReturns400() throws Exception {
        String otroIdMismoEmail = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E002\"")
                .replace("EMP-2026-001", "EMP-2026-999");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otroIdMismoEmail))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("El email 'juan.perez@empresa.com' ya está registrado"));
    }

    @Test
    @Order(5)
        void duplicateEmployeeNumberReturns400() throws Exception {
        String otroIdOtroEmail = JUAN
                .replace("\"id\": \"E001\"", "\"id\": \"E003\"")
                .replace("juan.perez@empresa.com", "pedro.lopez@empresa.com");

        mockMvc.perform(post("/empleados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otroIdOtroEmail))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
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

    @Test
    @Order(19)
    void auditEndpointFiltersRetiredEmployeesByDateRangeAndVerifiesLogicalDelete() throws Exception {
        // 1. Verificar que antes del retiro, la auditoría no incluye a E001
        mockMvc.perform(get("/empleados/auditoria?desde=2020-01-01&hasta=2030-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == 'E001')]").doesNotExist());

        // 2. Ejecutar baja lógica de E001 (DELETE /empleados/E001)
        mockMvc.perform(delete("/empleados/E001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("E001"))
                .andExpect(jsonPath("$.employeeId").value("E001"))
                .andExpect(jsonPath("$.estado").value("RETIRADO"))
                .andExpect(jsonPath("$.fechaRetiro").isNotEmpty());

        // 3. Demostrar que el registro NO fue eliminado físicamente (GET /empleados/E001 responde 200 OK)
        mockMvc.perform(get("/empleados/E001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("E001"))
                .andExpect(jsonPath("$.employeeId").value("E001"))
                .andExpect(jsonPath("$.nombre").value("Juan"))
                .andExpect(jsonPath("$.estado").value("RETIRADO"))
                .andExpect(jsonPath("$.fechaRetiro").isNotEmpty());

        // 4. Consultar auditoría en rango que cubre la fecha actual: debe retornar a E001
        mockMvc.perform(get("/empleados/auditoria?desde=2026-01-01&hasta=2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.employeeId == 'E001')].nombre").value("Juan"))
                .andExpect(jsonPath("$[?(@.employeeId == 'E001')].estado").value("RETIRADO"))
                .andExpect(jsonPath("$[?(@.employeeId == 'E001')].fechaRetiro").isNotEmpty());

        // 5. Consultar auditoría en un rango de fechas fuera del rango de retiro: no debe retornar registros
        mockMvc.perform(get("/empleados/auditoria?desde=2024-01-01&hasta=2024-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 6. Probar alias de parámetros fechaInicio y fechaFin
        mockMvc.perform(get("/empleados/auditoria?fechaInicio=2026-01-01&fechaFin=2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.employeeId == 'E001')].nombre").value("Juan"));
    }

    @Test
    @Order(20)
    void auditEndpointValidatesDateParameters() throws Exception {
        // Validación: desde > hasta debe retornar 400
        mockMvc.perform(get("/empleados/auditoria?desde=2026-12-31&hasta=2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no puede ser posterior a la fecha 'hasta'")));

        // Validación: fecha con formato inválido debe retornar 400
        mockMvc.perform(get("/empleados/auditoria?desde=no-es-fecha&hasta=2026-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Validación: parámetros faltantes debe retornar 400
        mockMvc.perform(get("/empleados/auditoria"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @Order(21)
    void updateEmployeeViaPutSuccessfully() throws Exception {
        String updatePayload = """
                {
                  "cargo": "Principal Architect",
                  "area": "Innovación"
                }
                """;

        mockMvc.perform(put("/empleados/E099")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("E099"))
                .andExpect(jsonPath("$.cargo").value("Principal Architect"))
                .andExpect(jsonPath("$.area").value("Innovación"))
                .andExpect(jsonPath("$.nombre").value("Carlos"));
    }

    @Test
    @Order(22)
    void updateEmployeeViaPatchSuccessfully() throws Exception {
        String patchPayload = """
                {
                  "cargo": "VP of Engineering"
                }
                """;

        mockMvc.perform(patch("/empleados/E099")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("E099"))
                .andExpect(jsonPath("$.cargo").value("VP of Engineering"))
                .andExpect(jsonPath("$.area").value("Innovación"));
    }

    @Test
    @Order(23)
    void getAllEmployeesSupportsFilteringByDepartmentStatusAndCargo() throws Exception {
        mockMvc.perform(get("/empleados?departamentoId=IT&estado=ACTIVO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == 'E099')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'E001')]").doesNotExist()); // E001 fue retirado en orden 19

        mockMvc.perform(get("/empleados?cargo=Engineering"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == 'E099')].cargo").value("VP of Engineering"));
    }

    @Test
    @Order(24)
    void getAllEmployeesSupportsPagination() throws Exception {
        mockMvc.perform(get("/empleados?page=1&limit=1&sortBy=id&order=ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.meta.total").isNumber())
                .andExpect(jsonPath("$.meta.totalPages").isNumber());
    }

    @Test
    @Order(25)
    void getAllEmployeesRejectsInvalidPaginationAndSorting() throws Exception {
        mockMvc.perform(get("/empleados?page=0&limit=10"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/empleados?page=1&limit=10&sortBy=password"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/empleados?page=1&limit=10&order=SIDEWAYS"))
                .andExpect(status().isBadRequest());
    }
}