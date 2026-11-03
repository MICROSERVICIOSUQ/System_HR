package com.microservicios.auth.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología AMQP del auth-service.
 *
 * <p>Declara de forma idempotente el exchange fanout {@code hr.events}, su propia
 * cola {@code auth.queue} y el binding entre ambas. La cola se declara aquí además
 * de en {@code infrastructure/rabbitmq/definitions.json} para que el servicio
 * arranque también contra un broker que no haya cargado esas definiciones.
 *
 * <p>Al ser un exchange fanout, el binding no lleva routing key: la cola recibe
 * todos los eventos del ecosistema y el listener descarta los que no le conciernen.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_HR_EVENTS = "hr.events";
    public static final String QUEUE_AUTH = "auth.queue";

    @Bean
    public FanoutExchange hrEventsExchange() {
        return new FanoutExchange(EXCHANGE_HR_EVENTS, true, false);
    }

    @Bean
    public Queue authQueue() {
        return new Queue(QUEUE_AUTH, true, false, false);
    }

    @Bean
    public Binding authQueueBinding(Queue authQueue, FanoutExchange hrEventsExchange) {
        return BindingBuilder.bind(authQueue).to(hrEventsExchange);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        // Igual que en employees-service: la propiedad
        // spring.rabbitmq.template.observation-enabled solo alcanza al template
        // auto-configurado, no a este. Sin esta línea, los eventos que publica
        // auth-service (usuario.creado, cuenta.desactivada) salen sin contexto
        // y notifications-service abre una traza nueva en lugar de continuar la
        // que viene del alta del empleado.
        template.setObservationEnabled(true);
        return template;
    }
}
