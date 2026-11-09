package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.events.EmployeeEventData;
import com.microservicios.employees.events.EventEnvelope;
import com.microservicios.employees.messaging.RabbitMQConfig;
import com.microservicios.employees.model.Employee;
import com.microservicios.employees.model.EmployeeStatus;
import com.microservicios.employees.repository.EmployeeRepository;
import com.microservicios.employees.service.EmployeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
class EmployeeEventPublisherTest {

    @Autowired
    private EmployeeService service;

    @Autowired
    private EmployeeRepository repository;

    @MockBean
    private DepartmentClient departmentClient;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        when(departmentClient.existsById("DEP-IT")).thenReturn(true);
        when(departmentClient.existsById("DEP-HR")).thenReturn(true);
    }

    private Employee createSampleEmployee(String id, String email, String num) {
        Employee emp = new Employee();
        emp.setId(id);
        emp.setNombre("Carlos");
        emp.setApellido("Rodríguez");
        emp.setEmail(email);
        emp.setEmployeeNumber(num);
        emp.setCargo("Ingeniero de Software");
        emp.setArea("Tecnología");
        emp.setDepartamentoId("DEP-IT");
        emp.setFechaIngreso(LocalDate.of(2026, 3, 1));
        return emp;
    }

    @Test
    @DisplayName("1. Creación exitosa publica evento empleado.creado en hr.events con envelope estándar")
    @SuppressWarnings("unchecked")
    void employeeCreatedPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-001", "carlos@empresa.com", "NUM-001");

        Employee saved = service.register(emp);
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);

        ArgumentCaptor<EventEnvelope<EmployeeEventData>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_HR_EVENTS),
                eq(""),
                captor.capture()
        );

        EventEnvelope<EmployeeEventData> envelope = captor.getValue();
        assertThat(envelope).isNotNull();
        assertThat(envelope.type()).isEqualTo("empleado.creado");
        assertThat(envelope.version()).isEqualTo("1.0");
        assertThat(envelope.producer()).isEqualTo("employees-service");
        assertThat(envelope.occurredAt()).isNotBlank();

        // El id del evento debe ser un UUID único, NO el id del empleado
        assertThat(envelope.id()).isNotEqualTo(emp.getId());
        assertThat(UUID.fromString(envelope.id())).isNotNull();

        // Data payload
        EmployeeEventData data = envelope.data();
        assertThat(data.id()).isEqualTo("EMP-001");
        assertThat(data.nombre()).isEqualTo("Carlos");
        assertThat(data.apellido()).isEqualTo("Rodríguez");
        assertThat(data.email()).isEqualTo("carlos@empresa.com");
        assertThat(data.departamentoId()).isEqualTo("DEP-IT");
        assertThat(data.cargo()).isEqualTo("Ingeniero de Software");
        assertThat(data.fechaIngreso()).isEqualTo("2026-03-01");
        assertThat(data.fechaRetiro()).isNull();
    }

    @Test
    @DisplayName("2. Actualización exitosa publica evento empleado.actualizado en hr.events")
    @SuppressWarnings("unchecked")
    void employeeUpdatedPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-002", "ana@empresa.com", "NUM-002");
        service.register(emp);
        reset(rabbitTemplate);

        Employee updateData = new Employee();
        updateData.setCargo("Líder Técnico");
        updateData.setDepartamentoId("DEP-HR");

        Employee updated = service.update("EMP-002", updateData);
        assertThat(updated.getCargo()).isEqualTo("Líder Técnico");
        assertThat(updated.getDepartamentoId()).isEqualTo("DEP-HR");

        ArgumentCaptor<EventEnvelope<EmployeeEventData>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_HR_EVENTS),
                eq(""),
                captor.capture()
        );

        EventEnvelope<EmployeeEventData> envelope = captor.getValue();
        assertThat(envelope.type()).isEqualTo("empleado.actualizado");
        assertThat(envelope.producer()).isEqualTo("employees-service");
        assertThat(envelope.data().cargo()).isEqualTo("Líder Técnico");
        assertThat(envelope.data().departamentoId()).isEqualTo("DEP-HR");
        assertThat(envelope.data().fechaRetiro()).isNull();
    }

    @Test
    @DisplayName("3. Retiro lógico mediante DELETE publica evento empleado.retirado con fechaRetiro y estado RETIRADO")
    @SuppressWarnings("unchecked")
    void employeeRetiredPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-003", "luis@empresa.com", "NUM-003");
        service.register(emp);
        reset(rabbitTemplate);

        Employee retired = service.retire("EMP-003");
        assertThat(retired.getStatus()).isEqualTo(EmployeeStatus.RETIRED);

        ArgumentCaptor<EventEnvelope<EmployeeEventData>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_HR_EVENTS),
                eq(""),
                captor.capture()
        );

        EventEnvelope<EmployeeEventData> envelope = captor.getValue();
        assertThat(envelope.type()).isEqualTo("empleado.retirado");
        assertThat(envelope.producer()).isEqualTo("employees-service");
        assertThat(envelope.data().id()).isEqualTo("EMP-003");

        // El evento lleva la fecha Y hora del retiro, tomada de la entidad.
        //
        // Antes se comparaba con LocalDate.now().toString(), que pasaba por una
        // coincidencia: el código usaba ese mismo reloj en lugar del valor
        // almacenado. Al corregirlo, la prueba tiene que mirar lo que de verdad
        // importa — que el evento informe del instante que quedó guardado.
        String fechaRetiroEvento = envelope.data().fechaRetiro();
        assertThat(fechaRetiroEvento)
                .as("ISO-8601 con hora, no solo la fecha")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
        assertThat(LocalDateTime.parse(fechaRetiroEvento).toLocalDate())
                .isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("4. Fallo de validación/departamento inexistente NO publica ningún evento en RabbitMQ")
    void validationFailureDoesNotPublishEvent() {
        when(departmentClient.existsById("DEP-INVALID")).thenReturn(false);

        Employee emp = createSampleEmployee("EMP-004", "error@empresa.com", "NUM-004");
        emp.setDepartamentoId("DEP-INVALID");

        assertThatThrownBy(() -> service.register(emp))
                .isInstanceOf(RuntimeException.class);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("5. Si RabbitMQ falla, el error se registra sin lanzar excepción y el empleado permanece persistido")
    void rabbitMQFailureDoesNotFailPersistence() {
        doThrow(new AmqpException("RabbitMQ connection refused"))
                .when(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_HR_EVENTS), eq(""), any(Object.class));

        Employee emp = createSampleEmployee("EMP-005", "resiliente@empresa.com", "NUM-005");

        // No debe lanzar excepción
        Employee saved = service.register(emp);

        // La persistencia se completó exitosamente
        assertThat(saved).isNotNull();
        assertThat(repository.findById("EMP-005")).isPresent();
        assertThat(repository.findById("EMP-005").get().getEmail()).isEqualTo("resiliente@empresa.com");
    }
}
