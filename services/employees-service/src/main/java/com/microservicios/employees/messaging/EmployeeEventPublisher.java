package com.microservicios.employees.messaging;

import com.microservicios.employees.events.EmployeeCreatedEvent;
import com.microservicios.employees.events.EmployeeEventData;
import com.microservicios.employees.events.EmployeeRetiredEvent;
import com.microservicios.employees.events.EmployeeUpdatedEvent;
import com.microservicios.employees.events.EventEnvelope;
import com.microservicios.employees.model.Employee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publicador de eventos de dominio para el ciclo de vida del empleado hacia RabbitMQ.
 * Publica exclusivamente al exchange fanout 'hr.events' sin routing key.
 */
@Component
public class EmployeeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public EmployeeEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publica el evento empleado.creado.
     */
    public void publishEmployeeCreated(Employee employee) {
        EventEnvelope<EmployeeEventData> envelope = EmployeeCreatedEvent.create(employee);
        sendEvent(envelope, employee.getId());
    }

    /**
     * Publica el evento empleado.actualizado.
     */
    public void publishEmployeeUpdated(Employee employee) {
        EventEnvelope<EmployeeEventData> envelope = EmployeeUpdatedEvent.create(employee);
        sendEvent(envelope, employee.getId());
    }

    /**
     * Publica el evento empleado.retirado.
     */
    public void publishEmployeeRetired(Employee employee) {
        EventEnvelope<EmployeeEventData> envelope = EmployeeRetiredEvent.create(employee);
        sendEvent(envelope, employee.getId());
    }

    private void sendEvent(EventEnvelope<EmployeeEventData> envelope, String employeeId) {
        log.info("Evento creado para publicación: type='{}', eventId='{}', employeeId='{}', producer='{}', exchange='{}'",
                envelope.type(), envelope.id(), employeeId, envelope.producer(), RabbitMQConfig.EXCHANGE_HR_EVENTS);

        try {
            // Fanout exchange: routing key vacía
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_HR_EVENTS, "", envelope);

            log.info("Evento publicado exitosamente: type='{}', eventId='{}', employeeId='{}'",
                    envelope.type(), envelope.id(), employeeId);
        } catch (Exception ex) {
            log.error("Error al publicar evento en RabbitMQ: type='{}', eventId='{}', employeeId='{}', exchange='{}', error='{}'",
                    envelope.type(), envelope.id(), employeeId, RabbitMQConfig.EXCHANGE_HR_EVENTS, ex.getMessage(), ex);
        }
    }
}
