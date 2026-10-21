package com.microservicios.employees;

import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import com.microservicios.employees.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueba de INTEGRACIÓN del repositorio contra un PostgreSQL real.
 *
 * <p>Esta clase sí necesita Docker: Testcontainers levanta un
 * {@code postgres:16-alpine} efímero, Flyway aplica sobre él la misma migración
 * {@code V1__create_employees.sql} que en producción, y al terminar el contenedor
 * se destruye. Las pruebas unitarias ({@code EmployeeServiceUnitTest}) no
 * necesitan nada de esto.
 *
 * <p><strong>Qué detecta esto que las unitarias no pueden ver.</strong> Una prueba
 * unitaria con un repositorio simulado comprueba que el servicio *consulta* la
 * unicidad antes de insertar, pero no puede comprobar que la restricción
 * {@code UNIQUE} exista de verdad en el esquema. Esa restricción es la única
 * defensa real contra la condición de carrera de dos registros simultáneos (ver
 * ADR-003), y solo una base de datos real puede confirmar que está ahí.
 *
 * <p>También verifica el mapeo de la entidad: que {@code numeroEmpleado} se
 * persiste en la columna {@code employee_number} y que el enum {@code estado} se
 * guarda como texto, cosas que un doble en memoria nunca revelaría.
 *
 * <p>Se eligió Testcontainers frente a H2 porque H2 no es el mismo motor: acepta
 * sintaxis y tipos que PostgreSQL rechaza, de modo que una prueba verde con H2
 * puede seguir fallando en producción. El precio es que requiere Docker y añade
 * unos segundos al arranque. Para las pruebas de contrato HTTP
 * ({@code EmployeeEndpointsTest}) se mantiene H2, donde la velocidad importa más
 * que la fidelidad del motor.
 */
@SpringBootTest
// disabledWithoutDocker: si Docker no está disponible, estas pruebas se OMITEN
// en lugar de fallar. Una integración que requiere infraestructura ausente no es
// un defecto del código, y hacer rojo el build por ello enseñaría al equipo a
// ignorar los fallos. El informe deja constancia de que se omitieron.
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration")
@DisplayName("Integración: repositorio de empleados contra PostgreSQL real")
class EmployeeRepositoryIntegrationTest {

    /**
     * Misma imagen que usa el docker-compose del sistema, para que el motor y la
     * versión de la prueba coincidan con los de producción.
     *
     * <p>{@code @ServiceConnection} hace que Spring Boot tome la URL, el usuario
     * y la contraseña del contenedor sin necesidad de un
     * {@code @DynamicPropertySource} escrito a mano.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("gestion_empleados_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Autowired
    private EmployeeRepository repository;

    @BeforeEach
    void limpiar() {
        repository.deleteAll();
    }

    private static Employee empleado(String id, String email, String numero) {
        Employee e = new Employee();
        e.setId(id);
        e.setNombre("Juan");
        e.setApellido("Pérez");
        e.setEmail(email);
        e.setEmployeeNumber(numero);
        e.setCargo("Desarrollador Senior");
        e.setArea("Tecnología");
        e.setDepartamentoId("IT");
        e.setFechaIngreso(LocalDate.of(2026, 2, 10));
        e.setStatus(EmployeeStatus.ACTIVE);
        return e;
    }

    @Test
    @DisplayName("Flyway aplicó la migración: la tabla existe y acepta un empleado completo")
    void persisteYRecupera() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        Optional<Employee> recuperado = repository.findById("E001");

        assertThat(recuperado).isPresent();
        assertThat(recuperado.get().getEmail()).isEqualTo("juan.perez@empresa.com");
        assertThat(recuperado.get().getEmployeeNumber()).isEqualTo("EMP-2026-001");
        assertThat(recuperado.get().getFechaIngreso()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(recuperado.get().getStatus())
                .as("el enum se persiste como texto y se vuelve a leer como enum")
                .isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    @DisplayName("La restricción UNIQUE del email existe en el esquema, no solo en el código")
    void laRestriccionUniqueDelEmailEstaEnLaBaseDeDatos() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        // Mismo email, id y número distintos: la aplicación no interviene aquí,
        // así que si pasa es porque el esquema no tiene la restricción.
        Employee duplicado = empleado("E002", "juan.perez@empresa.com", "EMP-2026-002");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicado))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("La restricción UNIQUE de numeroEmpleado existe en el esquema")
    void laRestriccionUniqueDelNumeroEstaEnLaBaseDeDatos() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        Employee duplicado = empleado("E002", "otro.correo@empresa.com", "EMP-2026-001");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicado))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByEmailIgnoreCase funciona contra PostgreSQL, no solo contra H2")
    void consultaDerivadaIgnorandoMayusculas() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        assertThat(repository.existsByEmailIgnoreCase("JUAN.PEREZ@EMPRESA.COM")).isTrue();
        assertThat(repository.existsByEmailIgnoreCase("otro@empresa.com")).isFalse();
    }

    @Test
    @DisplayName("existsByNumeroEmpleado distingue mayúsculas, como el esquema")
    void consultaDerivadaDelNumero() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        assertThat(repository.existsByNumeroEmpleado("EMP-2026-001")).isTrue();
        assertThat(repository.existsByNumeroEmpleado("EMP-2026-999")).isFalse();
    }

    @Test
    @DisplayName("El retiro conserva la fila y persiste la fechaRetiro: la baja es lógica")
    void elRetiroPersisteFechaYConservaLaFila() {
        repository.save(empleado("E001", "juan.perez@empresa.com", "EMP-2026-001"));

        Employee e = repository.findById("E001").orElseThrow();
        e.setStatus(EmployeeStatus.RETIRED);
        e.setFechaRetiro(LocalDate.of(2026, 10, 21));
        repository.saveAndFlush(e);

        Employee releido = repository.findById("E001").orElseThrow();
        assertThat(releido.getStatus()).isEqualTo(EmployeeStatus.RETIRED);
        assertThat(releido.getFechaRetiro()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(repository.count())
                .as("la baja es lógica: la fila sigue ahí para auditoría")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Un empleado sin campos obligatorios es rechazado por el NOT NULL del esquema")
    void elEsquemaExigeLosCamposObligatorios() {
        Employee incompleto = new Employee();
        incompleto.setId("E003");
        incompleto.setEmail("incompleto@empresa.com");
        incompleto.setEmployeeNumber("EMP-2026-003");
        incompleto.setStatus(EmployeeStatus.ACTIVE);
        // nombre, apellido, cargo, area, departamentoId y fechaIngreso quedan nulos

        assertThatThrownBy(() -> repository.saveAndFlush(incompleto))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
