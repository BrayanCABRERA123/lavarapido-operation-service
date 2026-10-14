package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cola de operations-service en carwash.events (ADR-004): solo booking.confirmed, para la
 * asignación automática (RF-008). El exchange lo declara el publicador de este mismo servicio.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class RabbitConsumerConfig {

    static final String QUEUE = "operations-service.events";

    @Bean
    Queue operationsEvents() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding bookingConfirmedBinding(Queue operationsEvents, TopicExchange carwashEvents) {
        return BindingBuilder.bind(operationsEvents).to(carwashEvents).with("booking.confirmed");
    }
}
