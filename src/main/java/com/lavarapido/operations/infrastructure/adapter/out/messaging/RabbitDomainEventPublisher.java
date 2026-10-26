package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.event.InspectionSharedEvent;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
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
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
// @Primary solo desambigua para el analisis estatico: la pareja con @ConditionalOnProperty opuesta nunca se carga a la vez
@Primary
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class RabbitDomainEventPublisher implements DomainEventPublisher {

    static final String EXCHANGE = "carwash.events";
    private static final Logger log = LoggerFactory.getLogger(RabbitDomainEventPublisher.class);
    private static final int CONTRACT_VERSION = 1;
    // OPS-08: cuánto se espera la confirmación del broker al compartir un informe
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

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

    /**
     * OPS-08: compartir el informe no guarda nada, así que se publica enseguida y se espera la
     * confirmación del broker. Si RabbitMQ no la acepta (caído, rechazo, sin cola o sin respuesta a
     * tiempo), 503 NOTIFICATION_NOT_SENT: el admin no ve "enviado" cuando el cliente no recibió nada.
     */
    @Override
    public void publishInspectionShared(InspectionSharedEvent event) {
        String eventId = UUID.randomUUID().toString();
        CorrelationData correlation = new CorrelationData(eventId);
        try {
            rabbit.send(EXCHANGE, InspectionSharedEvent.ROUTING_KEY, message(InspectionSharedEvent.EVENT_NAME, eventId,
                    event.bookingId(), event.occurredAt().toString(), payloadOf(event)), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw notSent(eventId, confirm.ack() ? "it reached no queue" : confirm.reason());
            }
            log.info("Published {} ({}) for booking {}", InspectionSharedEvent.EVENT_NAME, eventId, event.bookingId());
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            throw notSent(eventId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notSent(eventId, "interrupted");
        }
    }

    private static DependencyUnavailableException notSent(String eventId, String reason) {
        log.error("Could not publish {} ({}) to RabbitMQ: {}", InspectionSharedEvent.EVENT_NAME, eventId, reason);
        return new DependencyUnavailableException("NOTIFICATION_NOT_SENT", "The client could not be notified");
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
        try {
            // la confirmación del broker trae este id (onConfirm)
            rabbit.send(EXCHANGE, routingKey, message(eventName, eventId, bookingId, occurredAt, payload),
                    new CorrelationData(eventId));
            log.info("Published {} ({}) for booking {}", eventName, eventId, bookingId);
        } catch (AmqpException e) {
            log.error("Could not publish {} ({}) to RabbitMQ: {}", eventName, eventId, e.getMessage());
        }
    }

    private Message message(String eventName, String eventId, long bookingId, String occurredAt,
                            Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventName);
        envelope.put("aggregateId", String.valueOf(bookingId));
        envelope.put("occurredAt", occurredAt);
        envelope.put("version", CONTRACT_VERSION);
        envelope.put("payload", payload);
        return MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(eventId)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
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
