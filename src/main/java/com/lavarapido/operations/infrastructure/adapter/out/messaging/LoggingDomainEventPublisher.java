package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Con MESSAGING_ENABLED=false los eventos solo quedan en el log (desarrollo sin RabbitMQ). */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "false", matchIfMissing = true)
class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(ExecutionEvent event) {
        log.info("Domain event {} for booking {} (messaging disabled, not published)",
                event.type().eventName(), event.bookingId());
    }
}
