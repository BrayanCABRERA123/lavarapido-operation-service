package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica los eventos de ejecucion en carwash.events (ADR-004), con el mismo sobre que usa
 * security-service: {eventId, eventType, aggregateId, occurredAt, version, payload}.
 *
 * Se publica DESPUES del commit: si la asignacion no se guarda, nadie recibe el aviso. Si RabbitMQ
 * esta caido, la operacion no falla; queda el error en el log (el Outbox no esta adoptado, ADR-004).
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class RabbitDomainEventPublisher implements DomainEventPublisher {

    static final String EXCHANGE = "carwash.events";
    private static final Logger log = LoggerFactory.getLogger(RabbitDomainEventPublisher.class);
    private static final int CONTRACT_VERSION = 1;

    private final RabbitTemplate rabbit;
    private final JsonMapper json = JsonMapper.builder().build();

    RabbitDomainEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(ExecutionEvent event) {
        Runnable send = () -> send(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void send(ExecutionEvent event) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", event.type().eventName());
        envelope.put("aggregateId", String.valueOf(event.bookingId()));
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("version", CONTRACT_VERSION);
        envelope.put("payload", payloadOf(event));
        try {
            Message message = MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setMessageId(eventId)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbit.send(EXCHANGE, event.type().routingKey(), message);
            log.info("Published {} ({}) for booking {}", event.type().eventName(), eventId, event.bookingId());
        } catch (AmqpException e) {
            log.error("Could not publish {} ({}) to RabbitMQ: {}", event.type().eventName(), eventId, e.getMessage());
        }
    }

    /** Payload que espera notification-service (EventNotificationFactory, ADR-011). */
    static Map<String, Object> payloadOf(ExecutionEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", event.bookingId());
        payload.put("bookingCode", event.bookingCode());
        payload.put("customerUserId", event.customerUserId());
        payload.put("operatorUserId", event.operatorUserId());
        payload.put("scheduledStart", event.scheduledStart() == null ? null : event.scheduledStart().toString());
        return payload;
    }
}
