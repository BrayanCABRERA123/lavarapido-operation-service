package com.lavarapido.operations.domain.event;

import java.time.Instant;

/**
 * Evento de ejecucion que escucha notification-service (EventNotificationFactory): avisa al
 * operario y al cliente. type es el nombre del evento y routingKey la llave en carwash.events
 * (execution.operator_assigned...), como en cross-cutting.md §7.
 */
public record ExecutionEvent(Type type, long bookingId, String bookingCode, Long customerUserId,
                             Long operatorUserId, Instant scheduledStart, Instant occurredAt) {

    public enum Type {
        OPERATOR_ASSIGNED("OperatorAssigned", "execution.operator_assigned"),
        SERVICE_STARTED("ServiceStarted", "execution.service_started"),
        SERVICE_COMPLETED("ServiceCompleted", "execution.service_completed");

        private final String eventName;
        private final String routingKey;

        Type(String eventName, String routingKey) {
            this.eventName = eventName;
            this.routingKey = routingKey;
        }

        public String eventName() {
            return eventName;
        }

        public String routingKey() {
            return routingKey;
        }
    }
}
