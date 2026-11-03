package com.microservicios.employees.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de Spring AMQP para el microservicio de Empleados.
 *
 * Declara de forma idempotente el exchange fanout 'hr.events'.
 * No declara colas de consumidores (profiles.queue, notifications.queue, vacations.queue),
 * ya que pertenecen a la infraestructura central de RabbitMQ.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_HR_EVENTS = "hr.events";

    @Bean
    public FanoutExchange hrEventsExchange() {
        return new FanoutExchange(EXCHANGE_HR_EVENTS, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        // Propagación del contexto de traza por el broker (Reto 8).
        //
        // La propiedad spring.rabbitmq.template.observation-enabled solo afecta
        // al RabbitTemplate que auto-configura Spring Boot. Este bean se
        // construye a mano, así que hay que activarlo aquí: sin esta línea el
        // evento sale sin cabecera traceparent y la traza se corta en el
        // broker, con el agravante de que no aparece ningún error — la mitad
        // asíncrona del sistema simplemente deja de verse en Zipkin.
        template.setObservationEnabled(true);
        return template;
    }
}
