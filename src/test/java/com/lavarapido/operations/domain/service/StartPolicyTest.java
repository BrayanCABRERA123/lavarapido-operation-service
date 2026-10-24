package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("StartPolicy: desde cuándo el operario puede iniciar un servicio")
class StartPolicyTest {

    // reserva a las 9:00 de Bogotá = 14:00Z
    private static final BookingSnapshot BOOKING = new BookingSnapshot(50L, "RES-50", "CONFIRMED",
            LocalDate.of(2026, 10, 5), LocalTime.of(9, 0), LocalTime.of(10, 0),
            Instant.parse("2026-10-05T14:00:00Z"), Instant.parse("2026-10-05T15:00:00Z"), 7L, "Mazda 3", "ABC-123",
            new BigDecimal("35000"), List.of(new BookingSnapshot.Line(150L, "Premium")), null);

    private static final Duration LIMIT = StartPolicy.DEFAULT_LATE_LIMIT;

    @Test
    @DisplayName("se puede iniciar desde 15 minutos antes de la hora de la reserva")
    void startsFromFifteenMinutesBefore() {
        assertEquals(Instant.parse("2026-10-05T13:45:00Z"), StartPolicy.startableAt(BOOKING));
        assertDoesNotThrow(() -> StartPolicy.check(BOOKING, Instant.parse("2026-10-05T13:45:00Z"), LIMIT));
        assertDoesNotThrow(() -> StartPolicy.check(BOOKING, Instant.parse("2026-10-05T14:30:00Z"), LIMIT));
    }

    @Test
    @DisplayName("antes de eso (horas antes o el día anterior) se rechaza")
    void tooEarlyIsRejected() {
        ConflictException early = assertThrows(ConflictException.class,
                () -> StartPolicy.check(BOOKING, Instant.parse("2026-10-05T13:44:00Z"), LIMIT));
        assertEquals("SERVICE_TOO_EARLY", early.code());
        assertThrows(ConflictException.class, () -> StartPolicy.check(BOOKING, Instant.parse("2026-10-04T14:00:00Z"), LIMIT));
    }

    @Test
    @DisplayName("A-12: se puede iniciar tarde hasta 4 h después de la hora de fin; después se rechaza")
    void lateStartHasALimit() {
        // la reserva termina a las 15:00Z: el límite por defecto es las 19:00Z
        assertEquals(Instant.parse("2026-10-05T19:00:00Z"), StartPolicy.startableUntil(BOOKING, LIMIT));
        assertDoesNotThrow(() -> StartPolicy.check(BOOKING, Instant.parse("2026-10-05T19:00:00Z"), LIMIT));
        ConflictException late = assertThrows(ConflictException.class,
                () -> StartPolicy.check(BOOKING, Instant.parse("2026-10-05T19:01:00Z"), LIMIT));
        assertEquals("SERVICE_TOO_LATE", late.code());
        // la reserva de ayer tampoco
        assertThrows(ConflictException.class, () -> StartPolicy.check(BOOKING, Instant.parse("2026-10-06T14:00:00Z"), LIMIT));
        // el límite es configurable (START_LATE_LIMIT)
        assertDoesNotThrow(() -> StartPolicy.check(BOOKING, Instant.parse("2026-10-06T14:00:00Z"), Duration.ofDays(1)));
    }
}
