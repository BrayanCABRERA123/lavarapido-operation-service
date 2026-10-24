package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.DependencyUnavailableException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("BookingConfirmedListener (asignación automática al confirmarse una reserva)")
class BookingConfirmedListenerTest {

    private final OperationsUseCase operations = mock(OperationsUseCase.class);
    private final BookingConfirmedListener listener = new BookingConfirmedListener(operations, true);

    private static Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private static Message confirmed() {
        return message("{\"eventId\":\"e-1\",\"eventType\":\"BookingConfirmed\","
                + "\"payload\":{\"bookingId\":50,\"customerUserId\":7,\"preferredOperatorId\":3}}");
    }

    @Test
    @DisplayName("OPS-04: con booking caído el error se relanza para que RabbitMQ reintente y luego vaya a la DLQ")
    void bookingDownIsRetried() {
        given(operations.autoAssign(50L, 7L, 3)).willThrow(
                new DependencyUnavailableException("BOOKING_SERVICE_UNAVAILABLE", "booking-service is not available"));

        assertThrows(DependencyUnavailableException.class, () -> listener.onEvent(confirmed()));
    }

    @Test
    @DisplayName("OPS-04: la base caída y el candado ocupado también se reintentan")
    void transientFailuresAreRetried() {
        willThrow(new DataAccessResourceFailureException("db down")).given(operations).autoAssign(50L, 7L, 3);
        assertThrows(DataAccessResourceFailureException.class, () -> listener.onEvent(confirmed()));

        willThrow(new ConflictException("ASSIGNMENT_BUSY", "busy")).given(operations).autoAssign(50L, 7L, 3);
        assertThrows(ConflictException.class, () -> listener.onEvent(confirmed()));
    }

    @Test
    @DisplayName("un error de negocio no se reintenta: la reserva queda sin operario y avisa la alerta del admin")
    void businessErrorsAreNotRetried() {
        willThrow(new NotFoundException("OPERATOR_NOT_FOUND", "no")).given(operations).autoAssign(50L, 7L, 3);
        assertDoesNotThrow(() -> listener.onEvent(confirmed()));

        willReturn(Optional.empty()).given(operations).autoAssign(50L, 7L, 3);
        assertDoesNotThrow(() -> listener.onEvent(confirmed()));
    }

    @Test
    @DisplayName("un mensaje que no es JSON va directo a la DLQ; otros eventos se ignoran")
    void invalidOrForeignMessages() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onEvent(message("no es json")));

        listener.onEvent(message("{\"eventType\":\"BookingCancelled\",\"payload\":{\"bookingId\":50}}"));
        verify(operations, never()).autoAssign(anyLong(), any(), any());
    }
}
