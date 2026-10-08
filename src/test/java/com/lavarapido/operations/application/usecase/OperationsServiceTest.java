package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.AssignmentView;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.RatingView;
import com.lavarapido.operations.domain.port.out.BookingDirectory;
import com.lavarapido.operations.domain.port.out.DomainEventPublisher;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.domain.port.out.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OperationsService (asignacion, ejecucion y calificacion)")
class OperationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final long ADMIN = 1L;
    private static final long OPERATOR_USER = 30L;
    private static final long CLIENT = 7L;

    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final BookingDirectory bookings = mock(BookingDirectory.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private OperationsService service;

    private final Operator operator = Operator.restore(3, OPERATOR_USER, LocalDate.of(2026, 1, 1), true);

    @BeforeEach
    void setUp() {
        service = new OperationsService(operators, executions, bookings, users, events,
                Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/Bogota"));
        given(executions.saveAll(any(), anyLong())).willAnswer(invocation -> invocation.getArgument(0));
        given(operators.findById(3)).willReturn(Optional.of(operator));
        given(operators.findByUserId(OPERATOR_USER)).willReturn(Optional.of(operator));
        given(users.operators()).willReturn(List.of(
                new UserDirectory.UserSnapshot(OPERATOR_USER, "Oscar Operario", "op@gmail.com", "300", true)));
    }

    private static BookingSnapshot booking(String status) {
        return new BookingSnapshot(50L, "RES-000050", status, LocalDate.of(2026, 10, 5), LocalTime.of(9, 0),
                LocalTime.of(10, 0), Instant.parse("2026-10-05T14:00:00Z"), Instant.parse("2026-10-05T15:00:00Z"),
                CLIENT, "Mazda 3", "ABC-123", new BigDecimal("35000"),
                List.of(new BookingSnapshot.Line(500L, "Premium"), new BookingSnapshot.Line(501L, "Encerado")),
                new BookingSnapshot.Bay((short) 1, "BAY-01", "Bahía 1"));
    }

    @Test
    @DisplayName("asignar crea una ejecucion pendiente por linea y avisa a operario y cliente")
    void assignCreatesExecutions() {
        given(bookings.forAdmin(50L)).willReturn(Optional.of(booking("CONFIRMED")));
        given(bookings.forAdmin(any(LocalDate.class), any(LocalDate.class))).willReturn(List.of(booking("CONFIRMED")));
        given(operators.availability(3)).willReturn(
                List.of(new WeeklyAvailability((short) 1, LocalTime.of(8, 0), LocalTime.of(17, 0))));
        given(operators.absences(anyInt(), any(), any())).willReturn(List.of());
        given(executions.findByLineIds(any())).willReturn(List.of());

        AssignmentView view = service.assign(50L, 3, ADMIN);

        assertEquals(3, view.operatorId());
        assertEquals("Oscar Operario", view.operatorName());
        ArgumentCaptor<List<ServiceExecution>> saved = ArgumentCaptor.captor();
        verify(executions).saveAll(saved.capture(), eq(ADMIN));
        assertEquals(2, saved.getValue().size());
        ArgumentCaptor<ExecutionEvent> event = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(events).publish(event.capture());
        assertEquals(ExecutionEvent.Type.OPERATOR_ASSIGNED, event.getValue().type());
        assertEquals(CLIENT, event.getValue().customerUserId());
        assertEquals(OPERATOR_USER, event.getValue().operatorUserId());
    }

    @Test
    @DisplayName("una reserva que ya empezo o termino no se asigna")
    void cannotAssignStartedBooking() {
        given(bookings.forAdmin(50L)).willReturn(Optional.of(booking("IN_PROGRESS")));
        assertThrows(ConflictException.class, () -> service.assign(50L, 3, ADMIN));
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("el operario empieza su servicio: la reserva pasa a IN_PROGRESS y el cliente se entera")
    void operatorStarts() {
        given(bookings.forOperator(50L)).willReturn(Optional.of(booking("CONFIRMED")));
        given(executions.findByLineIds(any())).willReturn(List.of(
                ServiceExecution.restore(1L, 500L, 3, ExecutionStatus.PENDING, null, null, null, null, null, true),
                ServiceExecution.restore(2L, 501L, 3, ExecutionStatus.PENDING, null, null, null, null, null, true)));
        given(executions.findByOperator(3)).willReturn(List.of());

        assertEquals(ExecutionStatus.IN_PROGRESS, service.start(50L, OPERATOR_USER).status());
        verify(bookings).advance(50L, "IN_PROGRESS");
    }

    @Test
    @DisplayName("un operario no puede empezar un servicio que no es suyo (404)")
    void notMyService() {
        given(bookings.forOperator(50L)).willReturn(Optional.of(booking("CONFIRMED")));
        given(executions.findByLineIds(any())).willReturn(List.of(
                ServiceExecution.restore(1L, 500L, 9, ExecutionStatus.PENDING, null, null, null, null, null, true)));

        assertThrows(NotFoundException.class, () -> service.start(50L, OPERATOR_USER));
        verify(bookings, never()).advance(anyLong(), any());
    }

    @Test
    @DisplayName("no se empiezan dos lavados a la vez")
    void busyOperator() {
        given(bookings.forOperator(50L)).willReturn(Optional.of(booking("CONFIRMED")));
        given(executions.findByLineIds(any())).willReturn(List.of(
                ServiceExecution.restore(1L, 500L, 3, ExecutionStatus.PENDING, null, null, null, null, null, true)));
        given(executions.findByOperator(3)).willReturn(List.of(
                ServiceExecution.restore(9L, 900L, 3, ExecutionStatus.IN_PROGRESS, NOW, null, null, null, null, true)));

        assertEquals("OPERATOR_BUSY", assertThrows(ConflictException.class, () -> service.start(50L, OPERATOR_USER)).code());
    }

    @Test
    @DisplayName("RF-012: el cliente califica su reserva terminada y queda en todas sus lineas")
    void clientRates() {
        given(bookings.forCustomer(50L)).willReturn(Optional.of(booking("COMPLETED")));
        given(executions.findByLineIds(any())).willReturn(List.of(
                ServiceExecution.restore(1L, 500L, 3, ExecutionStatus.COMPLETED, NOW, NOW, null, null, null, true),
                ServiceExecution.restore(2L, 501L, 3, ExecutionStatus.COMPLETED, NOW, NOW, null, null, null, true)));

        RatingView view = service.rate(50L, 5, "Excelente", CLIENT);

        assertEquals(5, view.rating());
        assertEquals("Excelente", view.comment());
        ArgumentCaptor<List<ServiceExecution>> saved = ArgumentCaptor.captor();
        verify(executions).saveAll(saved.capture(), eq(CLIENT));
        assertEquals(2, saved.getValue().stream().filter(e -> e.rating() != null).count());
    }

    @Test
    @DisplayName("no se califica una reserva sin terminar ni la de otro cliente")
    void cannotRate() {
        given(bookings.forCustomer(50L)).willReturn(Optional.of(booking("CONFIRMED")));
        assertThrows(ConflictException.class, () -> service.rate(50L, 5, null, CLIENT));

        given(bookings.forCustomer(60L)).willReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.rate(60L, 5, null, CLIENT));
    }

    @Test
    @DisplayName("una cuenta OPERATOR sin fila de operario se crea al listar")
    void provisionsOperators() {
        given(operators.findAll()).willReturn(List.of());
        given(operators.save(any(Operator.class), anyLong())).willAnswer(invocation -> {
            Operator created = invocation.getArgument(0);
            created.assignId(4);
            return created;
        });
        given(operators.availability(4)).willReturn(List.of());
        given(executions.findByOperator(4)).willReturn(List.of());

        assertEquals(4, service.operators().getFirst().operatorId());
    }
}
