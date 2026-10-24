package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.event.InspectionSharedEvent;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
 *
 * OPS-04: con publisher confirms (spring.rabbitmq.publisher-confirm-type: correlated) y mandatory, un
 * mensaje que el broker rechaza o que no llega a ninguna cola también queda en el log con su eventId,
 * en vez de perderse en silencio.
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
        // OPS-04: el broker confirma cada mensaje; los que rechaza o no puede enrutar quedan en el log
        rabbit.setMandatory(true);
        rabbit.setConfirmCallback(RabbitDomainEventPublisher::onConfirm);
        rabbit.setReturnsCallback(RabbitDomainEventPublisher::onReturned);
    }

    static void onConfirm(CorrelationData correlation, boolean ack, String cause) {
        if (!ack) {
            log.error("RabbitMQ rejected event {}: {}", correlation == null ? "?" : correlation.getId(), cause);
        }
    }

    static void onReturned(ReturnedMessage returned) {
        log.error("Event {} ({}) reached no queue: {} {}", returned.getMessage().getMessageProperties().getMessageId(),
                returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText());
    }

    @Override
    public void publish(ExecutionEvent event) {
        afterCommit(() -> send(event.type().eventName(), event.type().routingKey(), event.bookingId(),
                event.occurredAt().toString(), payloadOf(event)));
    }

    @Override
    public void publishInspectionShared(InspectionSharedEvent event) {
        afterCommit(() -> send(InspectionSharedEvent.EVENT_NAME, InspectionSharedEvent.ROUTING_KEY, event.bookingId(),
                event.occurredAt().toString(), payloadOf(event)));
    }

    private static void afterCommit(Runnable send) {
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

    private void send(String eventName, String routingKey, long bookingId, String occurredAt, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventName);
        envelope.put("aggregateId", String.valueOf(bookingId));
        envelope.put("occurredAt", occurredAt);
        envelope.put("version", CONTRACT_VERSION);
        envelope.put("payload", payload);
        try {
            Message message = MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setMessageId(eventId)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            // la confirmación del broker trae este id (onConfirm)
            rabbit.send(EXCHANGE, routingKey, message, new CorrelationData(eventId));
            log.info("Published {} ({}) for booking {}", eventName, eventId, bookingId);
        } catch (AmqpException e) {
            log.error("Could not publish {} ({}) to RabbitMQ: {}", eventName, eventId, e.getMessage());
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

    /** Payload de execution.inspection_shared: el resumen y el token del enlace; notification arma el texto. */
    static Map<String, Object> payloadOf(InspectionSharedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", event.bookingId());
        payload.put("bookingCode", event.bookingCode());
        payload.put("customerUserId", event.customerUserId());
        payload.put("plate", event.plate());
        payload.put("publicToken", event.publicToken());
        payload.put("phasesDone", event.phasesDone());
        payload.put("phasesTotal", event.phasesTotal());
        payload.put("findings", event.findings());
        payload.put("majorFindings", event.majorFindings());
        payload.put("areas", event.areas());
        return payload;
    }
}
