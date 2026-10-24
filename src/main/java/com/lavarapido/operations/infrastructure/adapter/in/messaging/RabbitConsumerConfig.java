package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cola de operations-service en carwash.events (ADR-004): solo booking.confirmed, para la
 * asignación automática (RF-008). El exchange lo declara el publicador de este mismo servicio.
 *
 * OPS-04: un mensaje que sigue fallando después de los reintentos va a la DLQ
 * operations-service.booking-confirmed.dlq (por el exchange compartido carwash.events.dlx, el mismo de
 * notification-service) en vez de perderse. La cola se llama distinto de la de antes
 * (operations-service.events) porque RabbitMQ no deja agregarle la DLX a una cola que ya existe;
 * LegacyQueueMigration pasa los mensajes que queden en la vieja y la borra.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class RabbitConsumerConfig {

    static final String EXCHANGE = "carwash.events";
    static final String QUEUE = "operations-service.booking-confirmed";
    static final String DEAD_LETTER_EXCHANGE = "carwash.events.dlx";
    static final String DEAD_LETTER_QUEUE = "operations-service.booking-confirmed.dlq";
    static final String ROUTING_KEY = "booking.confirmed";
    // la cola de antes, sin DLX: se vacía en la nueva y se borra al arrancar
    static final String LEGACY_QUEUE = "operations-service.events";

    @Bean
    Declarables operationsTopology(TopicExchange carwashEvents) {
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Binding deadLetters = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_QUEUE);
        Binding confirmed = BindingBuilder.bind(queue).to(carwashEvents).with(ROUTING_KEY);
        return new Declarables(deadLetterExchange, deadLetterQueue, queue, deadLetters, confirmed);
    }
}
