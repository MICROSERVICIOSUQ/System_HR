package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.exception.DepartmentNotFoundException;
import com.microservicios.employees.exception.EmailAlreadyRegisteredException;
import com.microservicios.employees.exception.EmployeeAlreadyRetiredException;
import com.microservicios.employees.exception.EmployeeNotFoundException;
import com.microservicios.employees.exception.EmployeeNumberAlreadyRegisteredException;
import com.microservicios.employees.exception.IdAlreadyRegisteredException;
import com.microservicios.employees.messaging.EmployeeEventPublisher;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import com.microservicios.employees.repository.EmployeeRepository;
import com.microservicios.employees.service.EmployeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link EmployeeService}.
 *
 * <p>No levantan el contexto de Spring, no abren conexiones y no necesitan Docker:
 * el repositorio, el cliente HTTP a departamentos y el publicador de eventos se
 * sustituyen por dobles de Mockito. Lo que se verifica es la decisión del
 * servicio —qué acepta, qué rechaza, en qué orden comprueba y qué publica—, no la
 * infraestructura.
 *
 * <p>La prueba del orden de validación no es un detalle cosmético: el servicio
 * comprueba primero lo local y deja la llamada HTTP a departamentos para el final,
 * de modo que un registro que ya va a fallar por duplicado no gaste un round-trip
 * con sus reintentos.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceUnitTest {

    @Mock
    private EmployeeRepository repository;

    @Mock
    private DepartmentClient departmentClient;

    @Mock
    private EmployeeEventPublisher eventPublisher;

    @InjectMocks
    private EmployeeService service;

    private Employee nuevo;

    @BeforeEach
    void prepararEmpleadoValido() {
        nuevo = empleado("E001", "juan.perez@empresa.com", "EMP-2026-001", "IT");
    }

    private static Employee empleado(String id, String email, String numero, String departamento) {
        Employee e = new Employee();
        e.setId(id);
        e.setNombre("Juan");
        e.setApellido("Pérez");
        e.setEmail(email);
        e.setEmployeeNumber(numero);
        e.setCargo("Desarrollador Senior");
        e.setArea("Tecnología");
        e.setDepartamentoId(departamento);
        e.setFechaIngreso(LocalDate.of(2026, 2, 10));
        return e;
    }

    /** El repositorio no conoce a nadie y el departamento existe. */
    private void repositorioVacioYDepartamentoValido() {
        when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(repository.existsByNumeroEmpleado(anyString())).thenReturn(false);
        when(repository.existsById(anyString())).thenReturn(false);
        when(departmentClient.existsById(anyString())).thenReturn(true);
        when(repository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("register — camino feliz")
    class Registro {

        @Test
        @DisplayName("fuerza el estado a ACTIVO, persiste y publica empleado.creado")
        void registroExitoso() {
            repositorioVacioYDepartamentoValido();

            Employee guardado = service.register(nuevo);

            assertThat(guardado.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            verify(repository).save(nuevo);
            verify(eventPublisher).publishEmployeeCreated(guardado);
        }

        @Test
        @DisplayName("el estado que envíe el cliente se ignora: siempre queda ACTIVO")
        void normalizaElEstadoRecibido() {
            repositorioVacioYDepartamentoValido();
            nuevo.setStatus(EmployeeStatus.RETIRED);

            Employee guardado = service.register(nuevo);

            assertThat(guardado.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        }

        @Test
        @DisplayName("un fallo al publicar el evento no revierte la persistencia")
        void elFalloDelBrokerNoRompeElRegistro() {
            repositorioVacioYDepartamentoValido();
            doThrow(new RuntimeException("broker caído"))
                    .when(eventPublisher).publishEmployeeCreated(any());

            Employee guardado = service.register(nuevo);

            assertThat(guardado.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            verify(repository).save(nuevo);
        }
    }

    @Nested
    @DisplayName("register — unicidad")
    class Unicidad {

        @Test
        @DisplayName("email duplicado: lanza EmailAlreadyRegisteredException y no persiste")
        void emailDuplicado() {
            when(repository.existsByEmailIgnoreCase("juan.perez@empresa.com")).thenReturn(true);

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(EmailAlreadyRegisteredException.class)
                    .hasMessageContaining("juan.perez@empresa.com")
                    .hasMessageContaining("ya está registrado");

            verify(repository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("numeroEmpleado duplicado: lanza su excepción y no persiste")
        void numeroEmpleadoDuplicado() {
            when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(repository.existsByNumeroEmpleado("EMP-2026-001")).thenReturn(true);

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(EmployeeNumberAlreadyRegisteredException.class)
                    .hasMessageContaining("EMP-2026-001");

            verify(repository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("id duplicado: lanza su excepción y no sobrescribe al empleado anterior")
        void idDuplicado() {
            when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(repository.existsByNumeroEmpleado(anyString())).thenReturn(false);
            when(repository.existsById("E001")).thenReturn(true);

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(IdAlreadyRegisteredException.class)
                    .hasMessageContaining("E001");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("el email se compara ignorando mayúsculas")
        void emailSeComparaIgnorandoMayusculas() {
            when(repository.existsByEmailIgnoreCase("JUAN.PEREZ@EMPRESA.COM")).thenReturn(true);
            nuevo.setEmail("JUAN.PEREZ@EMPRESA.COM");

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);
        }
    }

    @Nested
    @DisplayName("register — validación del departamento y orden de comprobaciones")
    class Departamento {

        @Test
        @DisplayName("departamento inexistente: lanza DepartmentNotFoundException")
        void departamentoInexistente() {
            when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(repository.existsByNumeroEmpleado(anyString())).thenReturn(false);
            when(repository.existsById(anyString())).thenReturn(false);
            when(departmentClient.existsById("IT")).thenReturn(false);

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("IT");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("un duplicado local NO llega a consultar departamentos por HTTP")
        void noGastaLlamadaHttpSiYaVaAFallarLocalmente() {
            when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.register(nuevo))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);

            // Lo relevante: el cliente HTTP y sus reintentos no se tocaron.
            verifyNoInteractions(departmentClient);
        }
    }

    @Nested
    @DisplayName("retire — transición a RETIRADO")
    class Retiro {

        @Test
        @DisplayName("cambia el estado a RETIRADO, fija la fechaRetiro y publica el evento")
        void retiroFijaEstadoYFecha() {
            Employee activo = empleado("E001", "juan.perez@empresa.com", "EMP-2026-001", "IT");
            activo.setStatus(EmployeeStatus.ACTIVE);
            when(repository.findById("E001")).thenReturn(Optional.of(activo));
            when(repository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

            Employee retirado = service.retire("E001");

            assertThat(retirado.getStatus()).isEqualTo(EmployeeStatus.RETIRED);
            // fechaRetiro es LocalDateTime desde la migración V3, así que no se
            // puede comparar con una igualdad exacta: entre la llamada y la
            // aserción pasan milisegundos. Se comprueba el día y que el instante
            // sea reciente, que es lo que la regla de negocio promete.
            assertThat(retirado.getFechaRetiro())
                    .as("la baja es lógica: queda constancia de cuándo ocurrió")
                    .isNotNull();
            assertThat(retirado.getFechaRetiro().toLocalDate())
                    .as("el retiro se registra con la fecha de hoy")
                    .isEqualTo(LocalDate.now());
            assertThat(retirado.getFechaRetiro())
                    .as("y con la hora exacta del hecho, no a medianoche")
                    .isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
            verify(eventPublisher).publishEmployeeRetired(retirado);
        }

        @Test
        @DisplayName("un empleado ya retirado no se puede retirar de nuevo")
        void empleadoYaRetirado() {
            LocalDateTime original = LocalDateTime.of(2026, 3, 15, 9, 30, 12);
            Employee yaRetirado = empleado("E001", "juan.perez@empresa.com", "EMP-2026-001", "IT");
            yaRetirado.setStatus(EmployeeStatus.RETIRED);
            yaRetirado.setFechaRetiro(original);
            when(repository.findById("E001")).thenReturn(Optional.of(yaRetirado));

            assertThatThrownBy(() -> service.retire("E001"))
                    .isInstanceOf(EmployeeAlreadyRetiredException.class)
                    .hasMessageContaining("E001")
                    .hasMessageContaining("ya está retirado");

            assertThat(yaRetirado.getFechaRetiro()).isEqualTo(original);
            verify(repository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("retirar un empleado inexistente lanza EmployeeNotFoundException")
        void retiroDeInexistente() {
            when(repository.findById("E999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.retire("E999"))
                    .isInstanceOf(EmployeeNotFoundException.class)
                    .hasMessageContaining("E999");

            verify(repository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("el empleado retirado conserva sus datos: la baja no borra el registro")
        void laBajaEsLogicaNoFisica() {
            Employee activo = empleado("E001", "juan.perez@empresa.com", "EMP-2026-001", "IT");
            activo.setStatus(EmployeeStatus.ACTIVE);
            when(repository.findById("E001")).thenReturn(Optional.of(activo));
            when(repository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

            service.retire("E001");

            ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
            verify(repository).save(captor.capture());
            Employee persistido = captor.getValue();

            assertThat(persistido.getEmail()).isEqualTo("juan.perez@empresa.com");
            assertThat(persistido.getEmployeeNumber()).isEqualTo("EMP-2026-001");
            assertThat(persistido.getNombre()).isEqualTo("Juan");
            // El tipo es explícito porque el repositorio extiende también
            // JpaSpecificationExecutor y delete(…) quedaría ambiguo.
            verify(repository, never()).delete(any(Employee.class));
        }

        @Test
        @DisplayName("un fallo al publicar no impide el retiro")
        void elFalloDelBrokerNoImpideElRetiro() {
            Employee activo = empleado("E001", "juan.perez@empresa.com", "EMP-2026-001", "IT");
            activo.setStatus(EmployeeStatus.ACTIVE);
            when(repository.findById("E001")).thenReturn(Optional.of(activo));
            when(repository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("broker caído"))
                    .when(eventPublisher).publishEmployeeRetired(any());

            Employee retirado = service.retire("E001");

            assertThat(retirado.getStatus()).isEqualTo(EmployeeStatus.RETIRED);
        }
    }

    @Nested
    @DisplayName("consultas")
    class Consultas {

        @Test
        @DisplayName("findById devuelve el empleado existente")
        void findByIdExistente() {
            when(repository.findById("E001")).thenReturn(Optional.of(nuevo));

            assertThat(service.findById("E001")).isSameAs(nuevo);
        }

        @Test
        @DisplayName("findById de un id desconocido lanza EmployeeNotFoundException con el id en el mensaje")
        void findByIdInexistente() {
            when(repository.findById("E999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("E999"))
                    .isInstanceOf(EmployeeNotFoundException.class)
                    .hasMessage("El empleado con id E999 no existe");
        }

        @Test
        @DisplayName("findAll delega en el repositorio")
        void findAllDelega() {
            when(repository.findAll()).thenReturn(List.of(nuevo));

            assertThat(service.findAll()).containsExactly(nuevo);
        }
    }
}
