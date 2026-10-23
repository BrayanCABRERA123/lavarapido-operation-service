package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de entrada (RF-008): cuando booking-service confirma una reserva, operations le asigna
 * solo un operario libre (el que eligió el cliente si se puede, RF-010).
 *
 * Nunca relanza el error: si no se pudo asignar (booking no respondió, nadie libre), la reserva queda
 * sin operario y el administrador recibe la alerta de reserva sin operario (ADR-011 §14). Así un
 * mensaje no se reintenta para siempre.
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
        try {
            JsonNode event = json.readTree(message.getBody());
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
            operations.autoAssign(bookingId, customer, preferred).ifPresentOrElse(
                    assignment -> log.info("Booking {} assigned automatically to operator {}{}", bookingId,
                            assignment.operatorId(), preferred != null && preferred == assignment.operatorId()
                                    ? " (chosen by the client)" : ""),
                    () -> log.info("Booking {} was not assigned automatically (already assigned or nobody free)",
                            bookingId));
        } catch (RuntimeException e) {
            log.warn("Automatic assignment failed: {}", e.getMessage());
        }
    }
}
