package com.microservicios.employees;

import com.microservicios.employees.client.DepartmentClient;
import com.microservicios.employees.events.EmployeeEventData;
import com.microservicios.employees.events.EventEnvelope;
import com.microservicios.employees.messaging.OutboxEvento;
import com.microservicios.employees.messaging.OutboxRepository;
import com.microservicios.employees.messaging.PublicadorDeOutbox;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Publicación de eventos a través del outbox transaccional.
 *
 * <p><strong>Lo que cambió respecto a la versión anterior de esta clase.</strong>
 * Antes, cada operación de negocio publicaba en RabbitMQ en el acto, así que
 * bastaba registrar un empleado y comprobar el {@code convertAndSend}. Ahora el
 * evento se guarda en la tabla outbox dentro de la misma transacción y el envío
 * lo hace {@link PublicadorDeOutbox} en un ciclo aparte, de modo que la prueba
 * tiene dos tiempos: primero se comprueba que el evento quedó encolado, y
 * después se ejecuta el publicador a mano y se comprueba lo que sale al broker.
 *
 * <p>Las aserciones sobre el contenido del sobre —tipo, versión, productor, el
 * {@code id} como UUID distinto del id del empleado, el payload— se conservan
 * tal cual: el outbox cambia <em>cuándo</em> se envía, no <em>qué</em>.
 *
 * <p>El publicador se invoca explícitamente porque su {@code @Scheduled} está
 * desactivado en las pruebas (ver {@code outbox.retraso-inicial-ms} en
 * {@code src/test/resources/application.properties}). Un ciclo disparándose a
 * mitad de una prueba haría que el resultado dependiera del reloj.
 */
@SpringBootTest
class EmployeeEventPublisherTest {

    @Autowired
    private EmployeeService service;

    @Autowired
    private EmployeeRepository repository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PublicadorDeOutbox publicadorDeOutbox;

    @MockBean
    private DepartmentClient departmentClient;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        outboxRepository.deleteAll();
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

    /** Ejecuta el publicador y devuelve el único sobre que salió al broker. */
    @SuppressWarnings("unchecked")
    private EventEnvelope<EmployeeEventData> publicarYCapturarElSobre() {
        publicadorDeOutbox.publicarPendientes();

        ArgumentCaptor<EventEnvelope<EmployeeEventData>> captor =
                ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_HR_EVENTS),
                eq(""),
                captor.capture()
        );
        return captor.getValue();
    }

    @Test
    @DisplayName("1. El alta encola empleado.creado y el publicador lo envía a hr.events con el sobre estándar")
    void employeeCreatedPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-001", "carlos@empresa.com", "NUM-001");

        Employee saved = service.register(emp);
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);

        // Primer tiempo: el evento está en el outbox y todavía no en el broker.
        List<OutboxEvento> pendientes = outboxRepository.findAll();
        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.get(0).getTipo()).isEqualTo("empleado.creado");
        assertThat(pendientes.get(0).getAgregadoId()).isEqualTo("EMP-001");
        assertThat(pendientes.get(0).getPublicadoEn())
                .as("recién encolado, nadie lo ha publicado aún")
                .isNull();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));

        // Segundo tiempo: el publicador lo envía.
        EventEnvelope<EmployeeEventData> envelope = publicarYCapturarElSobre();

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

        // Y queda marcado, para que el ciclo siguiente no lo reenvíe.
        assertThat(outboxRepository.findAll().get(0).getPublicadoEn()).isNotNull();
        assertThat(publicadorDeOutbox.pendientes()).isZero();
    }

    @Test
    @DisplayName("2. La actualización encola y publica empleado.actualizado")
    void employeeUpdatedPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-002", "ana@empresa.com", "NUM-002");
        service.register(emp);
        publicadorDeOutbox.publicarPendientes();
        reset(rabbitTemplate);

        Employee updateData = new Employee();
        updateData.setCargo("Líder Técnico");
        updateData.setDepartamentoId("DEP-HR");

        Employee updated = service.update("EMP-002", updateData);
        assertThat(updated.getCargo()).isEqualTo("Líder Técnico");
        assertThat(updated.getDepartamentoId()).isEqualTo("DEP-HR");

        EventEnvelope<EmployeeEventData> envelope = publicarYCapturarElSobre();
        assertThat(envelope.type()).isEqualTo("empleado.actualizado");
        assertThat(envelope.producer()).isEqualTo("employees-service");
        assertThat(envelope.data().cargo()).isEqualTo("Líder Técnico");
        assertThat(envelope.data().departamentoId()).isEqualTo("DEP-HR");
        assertThat(envelope.data().fechaRetiro()).isNull();
    }

    @Test
    @DisplayName("3. El retiro lógico encola y publica empleado.retirado con la hora exacta")
    void employeeRetiredPublishesEvent() {
        Employee emp = createSampleEmployee("EMP-003", "luis@empresa.com", "NUM-003");
        service.register(emp);
        publicadorDeOutbox.publicarPendientes();
        reset(rabbitTemplate);

        Employee retired = service.retire("EMP-003");
        assertThat(retired.getStatus()).isEqualTo(EmployeeStatus.RETIRED);

        EventEnvelope<EmployeeEventData> envelope = publicarYCapturarElSobre();
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
    @DisplayName("4. Un departamento inexistente no encola nada: no hay evento que publicar")
    void validationFailureDoesNotPublishEvent() {
        when(departmentClient.existsById("DEP-INVALID")).thenReturn(false);

        Employee emp = createSampleEmployee("EMP-004", "error@empresa.com", "NUM-004");
        emp.setDepartamentoId("DEP-INVALID");

        assertThatThrownBy(() -> service.register(emp))
                .isInstanceOf(RuntimeException.class);

        assertThat(outboxRepository.findAll())
                .as("una validación que falla no deja eventos pendientes")
                .isEmpty();

        publicadorDeOutbox.publicarPendientes();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /**
     * La razón de ser del patrón: con el broker caído el evento no se pierde.
     *
     * <p>Esta prueba antes se llamaba «si RabbitMQ falla, el error se registra
     * sin lanzar excepción y el empleado permanece persistido», y eso era
     * exactamente el problema: el empleado quedaba creado y su evento no llegaba
     * nunca a nadie. Ahora el empleado sigue persistido —eso no cambia— pero el
     * evento queda pendiente y se entrega cuando el broker vuelve.
     */
    @Test
    @DisplayName("5. Con RabbitMQ caído el alta funciona, el evento queda pendiente y se publica al volver")
    void rabbitMQFailureDoesNotFailPersistence() {
        doThrow(new AmqpException("RabbitMQ connection refused"))
                .when(rabbitTemplate).convertAndSend(
                        eq(RabbitMQConfig.EXCHANGE_HR_EVENTS), eq(""), any(Object.class));

        Employee emp = createSampleEmployee("EMP-005", "resiliente@empresa.com", "NUM-005");

        // El alta no depende del broker: ya no se habla con él en esta operación.
        Employee saved = service.register(emp);
        assertThat(saved).isNotNull();
        assertThat(repository.findById("EMP-005")).isPresent();
        assertThat(repository.findById("EMP-005").get().getEmail())
                .isEqualTo("resiliente@empresa.com");

        // El publicador intenta enviarlo y no puede: el evento se queda, con su
        // intento contado y el motivo registrado para poder diagnosticarlo.
        publicadorDeOutbox.publicarPendientes();

        OutboxEvento pendiente = outboxRepository.findAll().get(0);
        assertThat(pendiente.getPublicadoEn())
                .as("el broker rechazó el envío: no puede quedar marcado como publicado")
                .isNull();
        assertThat(pendiente.getIntentos()).isEqualTo(1);
        assertThat(pendiente.getUltimoError()).contains("RabbitMQ connection refused");
        assertThat(publicadorDeOutbox.pendientes()).isEqualTo(1);

        // El broker vuelve y el evento se entrega sin que nadie intervenga.
        reset(rabbitTemplate);
        publicadorDeOutbox.publicarPendientes();

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_HR_EVENTS), eq(""), any(Object.class));
        assertThat(publicadorDeOutbox.pendientes())
                .as("entregado: ya no hay nada pendiente")
                .isZero();
    }
}
