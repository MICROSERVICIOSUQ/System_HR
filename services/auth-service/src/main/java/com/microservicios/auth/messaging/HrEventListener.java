package com.microservicios.auth.messaging;

import com.microservicios.auth.events.IncomingEvent;
import com.microservicios.auth.service.AccountLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code auth.queue}.
 *
 * <p>Un error de negocio no debe reencolar el mensaje indefinidamente: el envelope
 * ya quedó registrado como procesado y reintentarlo daría el mismo resultado. Solo
 * se propaga la excepción cuando el fallo es de infraestructura, para que Spring
 * AMQP reintente.
 */
@Component
public class HrEventListener {

    private static final Logger log = LoggerFactory.getLogger(HrEventListener.class);

    private final AccountLifecycleService lifecycle;

    public HrEventListener(AccountLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_AUTH)
    public void recibir(IncomingEvent evento) {
        try {
            AccountLifecycleService.Resultado resultado = lifecycle.procesar(evento);
            log.debug("[AUTH] Evento resuelto como {} | eventId='{}'",
                    resultado, evento != null ? evento.id() : "null");
        } catch (Exception ex) {
            log.error("[AUTH] [ERROR] Fallo procesando evento | eventId='{}' | tipo='{}' | causa='{}'",
                    evento != null ? evento.id() : "null",
                    evento != null ? evento.type() : "null",
                    ex.getMessage(), ex);
            throw ex;
        }
    }
}
