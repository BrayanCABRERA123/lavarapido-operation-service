package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AssignmentPolicy (RF-008/009: a quien se le puede asignar una reserva)")
class AssignmentPolicyTest {

    // lunes 5 de octubre de 2026, 9:00 a 10:00 en Bogota (14:00 a 15:00 UTC)
    private static final BookingSnapshot BOOKING = booking(1L, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");
    private static final List<WeeklyAvailability> MONDAY_MORNING =
            List.of(new WeeklyAvailability((short) 1, LocalTime.of(8, 0), LocalTime.of(12, 0)));
    private static final Operator OPERATOR = Operator.restore(3, 30L, LocalDate.of(2026, 1, 1), true);

    private static BookingSnapshot booking(long id, String start, String end) {
        Instant from = Instant.parse(start);
        Instant to = Instant.parse(end);
        return new BookingSnapshot(id, "RES-" + id, "CONFIRMED", LocalDate.of(2026, 10, 5),
                LocalTime.of(9, 0), LocalTime.of(10, 0), from, to, 7L, "Mazda 3", "ABC-123",
                new BigDecimal("35000"), List.of(new BookingSnapshot.Line(100 + id, "Premium")), null);
    }

    @Test
    @DisplayName("con turno, sin ausencias y libre, se puede asignar")
    void ok() {
        assertDoesNotThrow(() -> AssignmentPolicy.check(OPERATOR, MONDAY_MORNING, List.of(), BOOKING, List.of()));
    }

    @Test
    @DisplayName("sin turno ese dia o a esa hora no se asigna")
    void notOnShift() {
        List<WeeklyAvailability> afternoon =
                List.of(new WeeklyAvailability((short) 1, LocalTime.of(13, 0), LocalTime.of(18, 0)));
        ConflictException e = assertThrows(ConflictException.class,
                () -> AssignmentPolicy.check(OPERATOR, afternoon, List.of(), BOOKING, List.of()));
        assertEquals("OPERATOR_NOT_ON_SHIFT", e.code());
    }

    @Test
    @DisplayName("ausente o con otra reserva a la misma hora no se asigna")
    void absentOrBusy() {
        Absence sick = new Absence(1, Instant.parse("2026-10-05T13:00:00Z"), Instant.parse("2026-10-05T23:00:00Z"), "Incapacidad");
        assertEquals("OPERATOR_ABSENT", assertThrows(ConflictException.class,
                () -> AssignmentPolicy.check(OPERATOR, MONDAY_MORNING, List.of(sick), BOOKING, List.of())).code());

        BookingSnapshot other = booking(2L, "2026-10-05T14:30:00Z", "2026-10-05T15:30:00Z");
        assertEquals("OPERATOR_BUSY", assertThrows(ConflictException.class,
                () -> AssignmentPolicy.check(OPERATOR, MONDAY_MORNING, List.of(), BOOKING, List.of(other))).code());
    }

    @Test
    @DisplayName("un operario inactivo no recibe servicios")
    void inactive() {
        Operator inactive = Operator.restore(3, 30L, LocalDate.of(2026, 1, 1), false);
        assertEquals("OPERATOR_INACTIVE", assertThrows(ConflictException.class,
                () -> AssignmentPolicy.check(inactive, MONDAY_MORNING, List.of(), BOOKING, List.of())).code());
    }

    @Test
    @DisplayName("blockingReason da el mismo motivo que check, sin lanzar (para mostrar quien esta disponible)")
    void blockingReason() {
        assertEquals(null, AssignmentPolicy.blockingReason(OPERATOR, MONDAY_MORNING, List.of(), BOOKING, List.of()));
        assertEquals("OPERATOR_NOT_ON_SHIFT", AssignmentPolicy.blockingReason(OPERATOR, List.of(), List.of(), BOOKING, List.of()));
        Operator inactive = Operator.restore(4, 40L, LocalDate.of(2026, 1, 1), false);
        assertEquals("OPERATOR_INACTIVE", AssignmentPolicy.blockingReason(inactive, MONDAY_MORNING, List.of(), BOOKING, List.of()));
    }
}
