package com.microservicios.employees.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
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

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Publicador de eventos de dominio para el ciclo de vida del empleado.
 *
 * <p>Publica exclusivamente al exchange fanout 'hr.events' sin routing key.
 *
 * <p><strong>Ya no envía al broker directamente.</strong> Los eventos se
 * escriben en la tabla outbox dentro de la transacción del cambio de negocio y
 * los envía después {@link PublicadorDeOutbox}. Antes se hacía un
 * {@code convertAndSend} inmediato dentro de un try/catch que solo registraba el
 * fallo: si RabbitMQ estaba caído en ese segundo, el empleado quedaba creado y su
 * evento se perdía para siempre, sin que nadie lo reintentara. Ver
 * {@link OutboxEvento} para el razonamiento completo.
 */
@Component
public class EmployeeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public EmployeeEventPublisher(RabbitTemplate rabbitTemplate,
                                  OutboxRepository outboxRepository,
                                  ObjectMapper objectMapper, ObjectProvider<Tracer> tracer) {
        this.rabbitTemplate = rabbitTemplate;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer.getIfAvailable();
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

    /**
     * Escribe el evento en la tabla outbox, en la transacción de quien llama.
     *
     * <p>No abre transacción propia a propósito: tiene que participar en la del
     * método de negocio para que el cambio y su evento se confirmen juntos. Si
     * se llamara sin transacción activa, Spring Data abriría una para el
     * {@code save} y se perdería la garantía: por eso los métodos de
     * {@code EmployeeService} que publican eventos son {@code @Transactional}.
     */
    private void sendEvent(EventEnvelope<EmployeeEventData> envelope, String employeeId) {
        try {
            OutboxEvento event = new OutboxEvento(
                    envelope.id(),
                    envelope.type(),
                    employeeId,
                    objectMapper.writeValueAsString(envelope));
            if (tracer != null && tracer.currentSpan() != null) {
                var context = tracer.currentSpan().context();
                event.setTraceparent("00-" + context.traceId() + "-" + context.spanId()
                        + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00"));
            }
            outboxRepository.save(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            // Un sobre que no se puede serializar es un error de programación,
            // no una incidencia de entorno. Se propaga para que la transacción
            // se deshaga: es preferible que el alta falle a que el empleado
            // quede creado sin su evento, que es justo lo que este patrón evita.
            throw new IllegalStateException(
                    "No se pudo serializar el evento " + envelope.type(), error);
        }

        log.info("[OUTBOX] [ENCOLADO] {} | eventId='{}' | empleado='{}'",
                envelope.type(), envelope.id(), employeeId,
                kv("eventType", envelope.type()), kv("eventId", envelope.id()),
                kv("empleadoId", employeeId), kv("resultado", "encolado"));
    }

    /**
     * Publica directamente, sin pasar por el outbox.
     *
     * <p>Solo la usa {@link PublicadorDeOutbox} al vaciar la cola. Se mantiene
     * aquí para que el formato del mensaje —converter, exchange, routing key
     * vacía— se defina en un único sitio.
     */
    void enviarAlBroker(EventEnvelope<EmployeeEventData> envelope) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_HR_EVENTS, "", envelope);
    }
}
