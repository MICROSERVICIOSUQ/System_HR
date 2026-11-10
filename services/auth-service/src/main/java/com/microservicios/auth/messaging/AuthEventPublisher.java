package com.microservicios.auth.messaging;

import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.EventEnvelope;
import com.microservicios.auth.model.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

/**
 * Publica los cuatro eventos salientes del auth-service al exchange fanout
 * {@code hr.events} sin routing key.
 *
 * Los eventos se guardan con el cambio de cuenta. El publicador periódico
 * reintenta el envío y conserva su identificador original.
 */
@Component
public class AuthEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuthEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final OutboxCipher cipher;
    private final Tracer tracer;

    public AuthEventPublisher(RabbitTemplate rabbitTemplate, OutboxRepository repository,
                              ObjectMapper mapper, OutboxCipher cipher, ObjectProvider<Tracer> tracer) {
        this.rabbitTemplate = rabbitTemplate;
        this.repository = repository;
        this.mapper = mapper;
        this.cipher = cipher;
        this.tracer = tracer.getIfAvailable();
    }

    /** 3.4 — alta de cuenta: lleva el token de activación, nunca una contraseña. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarUsuarioCreado(UserAccount cuenta, String tokenActivacion) {
        publicar(EventEnvelope.of(
                AuthEvents.USUARIO_CREADO,
                new AuthEvents.UsuarioTokenData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), tokenActivacion)),
                cuenta.getEmpleadoId());
    }

    /** 3.5 — recuperación solicitada: lleva un token nuevo de vida corta. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarUsuarioRecuperacion(UserAccount cuenta, String tokenRecuperacion) {
        publicar(EventEnvelope.of(
                AuthEvents.USUARIO_RECUPERACION,
                new AuthEvents.UsuarioTokenData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), tokenRecuperacion)),
                cuenta.getEmpleadoId());
    }

    /** 3.6 — cuenta reactivada, con el motivo que lo provocó. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarCuentaActivada(UserAccount cuenta, String motivo) {
        publicar(EventEnvelope.of(
                AuthEvents.CUENTA_ACTIVADA,
                new AuthEvents.CuentaActivadaData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), motivo)),
                cuenta.getEmpleadoId());
    }

    /** 3.7 — cuenta desactivada; {@code permanente} separa el retiro de las vacaciones. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarCuentaDesactivada(UserAccount cuenta, String motivo, boolean permanente) {
        publicar(EventEnvelope.of(
                AuthEvents.CUENTA_DESACTIVADA,
                new AuthEvents.CuentaDesactivadaData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), motivo, permanente)),
                cuenta.getEmpleadoId());
    }

    private void publicar(EventEnvelope<?> envelope, String empleadoId) {
        try {
            String traceparent = null;
            if (tracer != null && tracer.currentSpan() != null) {
                var context = tracer.currentSpan().context();
                traceparent = "00-" + context.traceId() + "-" + context.spanId()
                        + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00");
            }
            repository.save(new OutboxEvento(envelope.id(), envelope.type(), empleadoId,
                    cipher.encrypt(mapper.writeValueAsString(envelope)), traceparent));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo guardar el evento de cuenta " + envelope.id(), ex);
        }
        log.info("[AUTH] [ENCOLADO] {} | eventId='{}' | empleado='{}'",
                envelope.type(), envelope.id(), empleadoId);
    }

    void enviarAlBroker(EventEnvelope<?> envelope) throws Exception {
        CorrelationData confirmation = new CorrelationData(envelope.id());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_HR_EVENTS, "", envelope, confirmation);
        var result = confirmation.getFuture().get(5, TimeUnit.SECONDS);
        if (!result.isAck() || confirmation.getReturned() != null) {
            throw new AmqpException("RabbitMQ no confirmó una entrega enrutada");
        }
    }
}
