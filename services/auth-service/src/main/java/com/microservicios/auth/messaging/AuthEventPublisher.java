package com.microservicios.auth.messaging;

import com.microservicios.auth.events.AuthEvents;
import com.microservicios.auth.events.EventEnvelope;
import com.microservicios.auth.model.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica los cuatro eventos salientes del auth-service al exchange fanout
 * {@code hr.events} sin routing key.
 *
 * <p>Igual que en employees-service, un fallo al publicar se registra pero no
 * revierte la operación de base de datos que ya tuvo éxito: la cuenta queda en el
 * estado correcto aunque la notificación no salga.
 */
@Component
public class AuthEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuthEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public AuthEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 3.4 — alta de cuenta: lleva el token de activación, nunca una contraseña. */
    public void publicarUsuarioCreado(UserAccount cuenta, String tokenActivacion) {
        publicar(EventEnvelope.of(
                AuthEvents.USUARIO_CREADO,
                new AuthEvents.UsuarioTokenData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), tokenActivacion)),
                cuenta.getEmpleadoId());
    }

    /** 3.5 — recuperación solicitada: lleva un token nuevo de vida corta. */
    public void publicarUsuarioRecuperacion(UserAccount cuenta, String tokenRecuperacion) {
        publicar(EventEnvelope.of(
                AuthEvents.USUARIO_RECUPERACION,
                new AuthEvents.UsuarioTokenData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), tokenRecuperacion)),
                cuenta.getEmpleadoId());
    }

    /** 3.6 — cuenta reactivada, con el motivo que lo provocó. */
    public void publicarCuentaActivada(UserAccount cuenta, String motivo) {
        publicar(EventEnvelope.of(
                AuthEvents.CUENTA_ACTIVADA,
                new AuthEvents.CuentaActivadaData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), motivo)),
                cuenta.getEmpleadoId());
    }

    /** 3.7 — cuenta desactivada; {@code permanente} separa el retiro de las vacaciones. */
    public void publicarCuentaDesactivada(UserAccount cuenta, String motivo, boolean permanente) {
        publicar(EventEnvelope.of(
                AuthEvents.CUENTA_DESACTIVADA,
                new AuthEvents.CuentaDesactivadaData(
                        cuenta.getEmpleadoId(), cuenta.getEmail(), motivo, permanente)),
                cuenta.getEmpleadoId());
    }

    private void publicar(EventEnvelope<?> envelope, String empleadoId) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_HR_EVENTS, "", envelope);
            log.info("[AUTH] [PUBLICADO] {} | eventId='{}' | empleado='{}'",
                    envelope.type(), envelope.id(), empleadoId);
        } catch (Exception ex) {
            log.error("[AUTH] [ERROR] No se pudo publicar {} | eventId='{}' | empleado='{}' | causa='{}'",
                    envelope.type(), envelope.id(), empleadoId, ex.getMessage(), ex);
        }
    }
}
