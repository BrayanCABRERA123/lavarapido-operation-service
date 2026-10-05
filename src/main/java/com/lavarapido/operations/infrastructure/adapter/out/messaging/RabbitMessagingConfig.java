package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara el exchange compartido carwash.events (ADR-004). Este servicio solo publica; las colas
 * las declara cada consumidor (notification-service...).
 */
@Configuration
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class RabbitMessagingConfig {

    @Bean
    TopicExchange carwashEvents() {
        return new TopicExchange(RabbitDomainEventPublisher.EXCHANGE, true, false);
    }
}
