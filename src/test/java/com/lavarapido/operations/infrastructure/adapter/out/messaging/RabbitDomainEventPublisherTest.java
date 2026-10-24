package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RabbitDomainEventPublisher (eventos de ejecución a carwash.events)")
class RabbitDomainEventPublisherTest {

    @Test
    @DisplayName("OPS-04: cada evento va con publisher confirms (su eventId) y mandatory")
    void eventsAreConfirmedByTheBroker() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        RabbitDomainEventPublisher publisher = new RabbitDomainEventPublisher(rabbit);
        verify(rabbit).setMandatory(true);
        verify(rabbit).setConfirmCallback(any());
        verify(rabbit).setReturnsCallback(any());

        // fuera de una transacción se publica enseguida
        publisher.publish(new ExecutionEvent(ExecutionEvent.Type.SERVICE_STARTED, 50L, "RES-000050", 7L, 30L,
                Instant.parse("2026-10-05T14:00:00Z"), Instant.parse("2026-10-05T13:55:00Z")));

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).send(eq("carwash.events"), eq("execution.service_started"), message.capture(), correlation.capture());
        assertEquals(message.getValue().getMessageProperties().getMessageId(), correlation.getValue().getId());
    }

    @Test
    @DisplayName("OPS-04: un rechazo del broker queda en el log sin romper nada")
    void aRejectedEventIsLogged() {
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(new CorrelationData("e-1"), false, "nack"));
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(null, false, "nack"));
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(new CorrelationData("e-2"), true, null));
    }
}
