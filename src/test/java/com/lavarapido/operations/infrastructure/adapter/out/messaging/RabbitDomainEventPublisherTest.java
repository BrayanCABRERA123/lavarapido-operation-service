package com.lavarapido.operations.infrastructure.adapter.out.messaging;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.event.InspectionSharedEvent;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

    private static InspectionSharedEvent shared() {
        return new InspectionSharedEvent(50L, "RES-000050", 7L, "ABC-123", "token", 5, 5, 1, 0, List.of("Puerta"),
                Instant.parse("2026-10-05T16:00:00Z"));
    }

    /** RabbitTemplate falso que responde la confirmación del broker como se le diga. */
    private static RabbitTemplate brokerThatConfirms(boolean ack, boolean returned) {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE",
                        "carwash.events", "execution.inspection_shared"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        return rabbit;
    }

    @Test
    @DisplayName("OPS-08: compartir el informe espera la confirmación del broker")
    void sharingWaitsForTheBroker() {
        RabbitTemplate rabbit = brokerThatConfirms(true, false);

        assertDoesNotThrow(() -> new RabbitDomainEventPublisher(rabbit).publishInspectionShared(shared()));
        verify(rabbit).send(eq("carwash.events"), eq("execution.inspection_shared"), any(Message.class),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("OPS-08: si el broker lo rechaza o no llega a ninguna cola, 503 NOTIFICATION_NOT_SENT")
    void sharingFailsWhenTheBrokerDoesNotTakeIt() {
        DependencyUnavailableException nack = assertThrows(DependencyUnavailableException.class,
                () -> new RabbitDomainEventPublisher(brokerThatConfirms(false, false)).publishInspectionShared(shared()));
        assertEquals("NOTIFICATION_NOT_SENT", nack.code());
        assertThrows(DependencyUnavailableException.class,
                () -> new RabbitDomainEventPublisher(brokerThatConfirms(true, true)).publishInspectionShared(shared()));

        RabbitTemplate down = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused"))).when(down)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(DependencyUnavailableException.class,
                () -> new RabbitDomainEventPublisher(down).publishInspectionShared(shared()));
    }

    @Test
    @DisplayName("OPS-08: con la mensajería apagada, compartir responde 503 (antes decía que se había avisado)")
    void sharingWithoutMessagingFails() {
        assertThrows(DependencyUnavailableException.class,
                () -> new LoggingDomainEventPublisher().publishInspectionShared(shared()));
    }

    @Test
    @DisplayName("OPS-04: un rechazo del broker queda en el log sin romper nada")
    void aRejectedEventIsLogged() {
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(new CorrelationData("e-1"), false, "nack"));
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(null, false, "nack"));
        assertDoesNotThrow(() -> RabbitDomainEventPublisher.onConfirm(new CorrelationData("e-2"), true, null));
    }
}
