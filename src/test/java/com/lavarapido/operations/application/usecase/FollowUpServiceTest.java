package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.port.in.FollowUpUseCase.FollowUpRun;
import com.lavarapido.operations.domain.port.in.FollowUpUseCase.ToCloseView;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.domain.service.FollowUpPolicy.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("FollowUpService (reservas que terminaron y nadie cerró)")
class FollowUpServiceTest {

    // las reservas de prueba van de 14:00Z a 15:00Z; ahora son las 16:30Z (pasó el margen de 60 min)
    private static final Instant NOW = Instant.parse("2026-10-05T16:30:00Z");
    private static final long ADMIN = 1L;
    private static final long OPERATOR_USER = 30L;

    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final BookingDirectory bookings = mock(BookingDirectory.class);
    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private FollowUpService service;

    @BeforeEach
    void setUp() {
        service = new FollowUpService(executions, bookings, operators, events, Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneId.of("America/Bogota"), 60);
        given(executions.saveAll(any(), anyLong())).willAnswer(invocation -> invocation.getArgument(0));
        given(operators.findById(3)).willReturn(Optional.of(Operator.restore(3, OPERATOR_USER, LocalDate.of(2026, 1, 1), true)));
    }

    private static BookingSnapshot booking(long id, String status, long lineId) {
        return new BookingSnapshot(id, "RES-0000" + id, status, LocalDate.of(2026, 10, 5), LocalTime.of(9, 0),
                LocalTime.of(10, 0), Instant.parse("2026-10-05T14:00:00Z"), Instant.parse("2026-10-05T15:00:00Z"),
                7L, "Mazda 3", "ABC-123", new BigDecimal("35000"), List.of(new BookingSnapshot.Line(lineId, "Premium")),
                null);
    }

    private static ServiceExecution line(long lineId, ExecutionStatus status, Instant startedAt) {
        return ServiceExecution.restore(lineId, lineId, 3, status, startedAt, null, null, null, null, true);
    }

    @Test
    @DisplayName("la que nunca tuvo operario se cierra como no asistió; la que tenía operario no, se avisa")
    void onlyBookingsWithoutOperatorAreClosedByThemselves() {
        BookingSnapshot noOperator = booking(60L, "CONFIRMED", 600L);
        BookingSnapshot withOperator = booking(61L, "CONFIRMED", 610L);
        ServiceExecution pending = line(610L, ExecutionStatus.PENDING, null);
        given(bookings.forService(any(LocalDate.class), any(LocalDate.class))).willReturn(List.of(noOperator, withOperator));
        given(executions.findByLineIds(any())).willReturn(List.of(pending));

        FollowUpRun run = service.followUp();

        assertEquals(new FollowUpRun(1, 1), run);
        verify(bookings).markUnattended(60L);
        verify(bookings, never()).markUnattended(61L);
        ArgumentCaptor<ExecutionEvent> event = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(events).publish(event.capture());
        assertEquals(ExecutionEvent.Type.SERVICE_NOT_STARTED, event.getValue().type());
        assertEquals(61L, event.getValue().bookingId());
        assertEquals(OPERATOR_USER, event.getValue().operatorUserId());
        assertEquals(NOW, pending.followUpNoticeAt());
    }

    @Test
    @DisplayName("el aviso sale una sola vez por reserva")
    void theNoticeIsSentOnlyOnce() {
        ServiceExecution noticed = line(610L, ExecutionStatus.PENDING, null);
        noticed.followUpNoticeSent(NOW.minusSeconds(300));
        given(bookings.forService(any(LocalDate.class), any(LocalDate.class))).willReturn(List.of(booking(61L, "CONFIRMED", 610L)));
        given(executions.findByLineIds(any())).willReturn(List.of(noticed));

        assertEquals(new FollowUpRun(0, 0), service.followUp());
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("si booking no la deja cerrar, la vuelta sigue con las demás")
    void aBookingThatCannotBeClosedDoesNotStopTheRun() {
        given(bookings.forService(any(LocalDate.class), any(LocalDate.class)))
                .willReturn(List.of(booking(60L, "CONFIRMED", 600L), booking(62L, "CONFIRMED", 620L)));
        given(executions.findByLineIds(any())).willReturn(List.of());
        willThrow(new ConflictException("BOOKING_STATE_CONFLICT", "no")).given(bookings).markUnattended(60L);

        assertEquals(new FollowUpRun(1, 0), service.followUp());
        verify(bookings).markUnattended(62L);
    }

    @Test
    @DisplayName("por cerrar: con operario y sin iniciar, o iniciada y sin finalizar; nunca la que no tuvo operario")
    void listsTheBookingsToClose() {
        given(bookings.forAdmin(any(LocalDate.class), any(LocalDate.class))).willReturn(List.of(
                booking(60L, "CONFIRMED", 600L), booking(61L, "CONFIRMED", 610L), booking(63L, "IN_PROGRESS", 630L),
                booking(64L, "COMPLETED", 640L)));
        given(executions.findByLineIds(any())).willReturn(List.of(
                line(610L, ExecutionStatus.PENDING, null),
                line(630L, ExecutionStatus.IN_PROGRESS, Instant.parse("2026-10-05T14:05:00Z")),
                line(640L, ExecutionStatus.COMPLETED, Instant.parse("2026-10-05T14:00:00Z"))));

        List<ToCloseView> toClose = service.toClose(null, null);

        assertEquals(List.of(new ToCloseView(61L, Reason.NOT_STARTED, 3), new ToCloseView(63L, Reason.NOT_FINISHED, 3)),
                toClose);
    }

    @Test
    @DisplayName("se atendió: la reserva avanza a completada y la línea queda terminada sin inventar horas")
    void closingAsAttendedCompletesWithoutInventingTimes() {
        ServiceExecution pending = line(610L, ExecutionStatus.PENDING, null);
        given(bookings.forAdmin(61L)).willReturn(Optional.of(booking(61L, "CONFIRMED", 610L)));
        given(executions.findByLineIds(List.of(610L))).willReturn(List.of(pending));

        service.closeAsAttended(61L, ADMIN);

        assertEquals(ExecutionStatus.COMPLETED, pending.status());
        assertNull(pending.startedAt());
        assertNull(pending.finishedAt());
        InOrder order = inOrder(bookings, executions);
        order.verify(bookings).advance(61L, "IN_PROGRESS");
        order.verify(bookings).advance(61L, "COMPLETED");
        order.verify(executions).saveAll(List.of(pending), ADMIN);
        // al cliente no se le avisa "ya puedes recogerlo" horas después
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("una iniciada sin finalizar solo se termina; una que no está por cerrar se rechaza")
    void closingAnUnfinishedServiceAndRejectingTheRest() {
        ServiceExecution running = line(630L, ExecutionStatus.IN_PROGRESS, Instant.parse("2026-10-05T14:05:00Z"));
        given(bookings.forAdmin(63L)).willReturn(Optional.of(booking(63L, "IN_PROGRESS", 630L)));
        given(executions.findByLineIds(List.of(630L))).willReturn(List.of(running));
        given(bookings.forAdmin(60L)).willReturn(Optional.of(booking(60L, "CONFIRMED", 600L)));
        given(executions.findByLineIds(List.of(600L))).willReturn(List.of());

        service.closeAsAttended(63L, ADMIN);

        assertEquals(ExecutionStatus.COMPLETED, running.status());
        assertNotNull(running.startedAt());
        verify(bookings, never()).advance(63L, "IN_PROGRESS");
        verify(bookings).advance(63L, "COMPLETED");
        ConflictException error = assertThrows(ConflictException.class, () -> service.closeAsAttended(60L, ADMIN));
        assertEquals("BOOKING_NOT_TO_CLOSE", error.code());
    }
}
