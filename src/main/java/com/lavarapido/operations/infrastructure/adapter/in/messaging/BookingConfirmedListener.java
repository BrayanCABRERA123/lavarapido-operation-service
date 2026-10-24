package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de entrada (RF-008): cuando booking-service confirma una reserva, operations le asigna
 * solo un operario libre (el que eligió el cliente si se puede, RF-010).
 *
 * OPS-04: si falla algo que se arregla solo (booking o security no respondieron, la base está caída o
 * otra asignación tenía el candado), el error se relanza: RabbitMQ lo reintenta
 * (spring.rabbitmq.listener.simple.retry) y, si sigue fallando, el mensaje queda en la DLQ
 * operations-service.booking-confirmed.dlq para revisarlo. Un error de negocio (nadie libre, la reserva
 * ya no se puede asignar) no se reintenta: la reserva queda sin operario y el administrador recibe la
 * alerta de reserva sin operario (ADR-011 §14). Un mensaje que no es JSON va directo a la DLQ.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class BookingConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedListener.class);

    private final OperationsUseCase operations;
    private final boolean enabled;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    BookingConfirmedListener(OperationsUseCase operations,
                             @Value("${app.auto-assign.enabled:true}") boolean enabled) {
        this.operations = operations;
        this.enabled = enabled;
    }

    @RabbitListener(queues = RabbitConsumerConfig.QUEUE)
    void onEvent(Message message) {
        JsonNode event;
        try {
            event = json.readTree(message.getBody());
        } catch (JacksonException e) {
            log.warn("Message on {} is not a valid event envelope, sent to the DLQ", RabbitConsumerConfig.QUEUE);
            throw new AmqpRejectAndDontRequeueException("Invalid event envelope", e);
        }
        if (!enabled || !"BookingConfirmed".equals(event.path("eventType").asString(""))) {
            return;
        }
        JsonNode payload = event.path("payload");
        long bookingId = payload.path("bookingId").asLong(0);
        if (bookingId <= 0) {
            return;
        }
        Long customer = payload.hasNonNull("customerUserId") ? payload.get("customerUserId").asLong() : null;
        Integer preferred = payload.hasNonNull("preferredOperatorId") ? payload.get("preferredOperatorId").asInt() : null;
        try {
            operations.autoAssign(bookingId, customer, preferred).ifPresentOrElse(
                    assignment -> log.info("Booking {} assigned automatically to operator {}{}", bookingId,
                            assignment.operatorId(), preferred != null && preferred == assignment.operatorId()
                                    ? " (chosen by the client)" : ""),
                    () -> log.info("Booking {} was not assigned automatically (already assigned or nobody free)",
                            bookingId));
        } catch (DependencyUnavailableException | DataAccessException | TransactionException e) {
            log.warn("Automatic assignment of booking {} failed, it will be retried: {}", bookingId, e.getMessage());
            throw e;
        } catch (ConflictException e) {
            // otra asignación tenía el candado: en el siguiente intento ya terminó
            if ("ASSIGNMENT_BUSY".equals(e.code())) {
                log.warn("Automatic assignment of booking {} waited too long for the lock, it will be retried", bookingId);
                throw e;
            }
            log.warn("Automatic assignment of booking {} not done: {}", bookingId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Automatic assignment of booking {} not done: {}", bookingId, e.getMessage());
        }
    }
}
