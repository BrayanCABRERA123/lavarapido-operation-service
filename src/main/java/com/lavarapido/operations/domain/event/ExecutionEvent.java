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
        SERVICE_COMPLETED("ServiceCompleted", "execution.service_completed"),
        // terminó la hora y nadie lo registró: se avisa al operario y a los administradores
        SERVICE_NOT_STARTED("ServiceNotStarted", "execution.service_not_started"),
        SERVICE_NOT_FINISHED("ServiceNotFinished", "execution.service_not_finished"),
        // E-12: al reprogramar, el operario ya no puede y nadie más está libre: la reserva queda sin
        // operario. operatorUserId es el operario que la tenía
        OPERATOR_UNASSIGNED("OperatorUnassigned", "execution.operator_unassigned");

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
