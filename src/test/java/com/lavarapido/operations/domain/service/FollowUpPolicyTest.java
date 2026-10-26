package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.service.FollowUpPolicy.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FollowUpPolicy (qué hacer con una reserva que terminó y nadie cerró)")
class FollowUpPolicyTest {

    private static final Instant END = Instant.parse("2026-10-05T15:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    private static BookingSnapshot booking(String status) {
        return new BookingSnapshot(50L, "RES-000050", status, LocalDate.of(2026, 10, 5), LocalTime.of(9, 0),
                LocalTime.of(10, 0), END.minus(HOUR), END, 7L, "Mazda 3", "ABC-123", new BigDecimal("35000"),
                List.of(new BookingSnapshot.Line(500L, "Premium")), null);
    }

    private static List<ServiceExecution> line(ExecutionStatus status) {
        return List.of(ServiceExecution.restore(1L, 500L, 3, status, null, null, null, null, null, true));
    }

    @Test
    @DisplayName("sin operario: no asistió, pero solo después del margen")
    void withoutOperatorItIsClosedAfterTheGrace() {
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("CONFIRMED"), List.of(), END.plusSeconds(1800), HOUR));
        assertEquals(Optional.of(Reason.NO_OPERATOR), FollowUpPolicy.of(booking("CONFIRMED"), List.of(), END.plus(HOUR), HOUR));
    }

    @Test
    @DisplayName("con operario y sin iniciar: por cerrar en cuanto termina la hora, nunca no asistió solo")
    void withOperatorItWaitsForTheAdmin() {
        List<ServiceExecution> pending = line(ExecutionStatus.PENDING);
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("CONFIRMED"), pending, END.minusSeconds(60), HOUR));
        assertEquals(Optional.of(Reason.NOT_STARTED), FollowUpPolicy.of(booking("CONFIRMED"), pending, END, HOUR));
        assertEquals(Optional.of(Reason.NOT_STARTED), FollowUpPolicy.of(booking("CONFIRMED"), pending, END.plus(Duration.ofDays(2)), HOUR));
    }

    @Test
    @DisplayName("iniciada y sin finalizar: por cerrar después del margen; terminada o cerrada: nada")
    void startedButNotFinished() {
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("IN_PROGRESS"), line(ExecutionStatus.IN_PROGRESS), END.plusSeconds(600), HOUR));
        assertEquals(Optional.of(Reason.NOT_FINISHED), FollowUpPolicy.of(booking("IN_PROGRESS"), line(ExecutionStatus.IN_PROGRESS), END.plus(HOUR), HOUR));
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("COMPLETED"), line(ExecutionStatus.COMPLETED), END.plus(HOUR), HOUR));
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("NO_SHOW"), line(ExecutionStatus.PENDING), END.plus(HOUR), HOUR));
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("CANCELLED"), List.of(), END.plus(HOUR), HOUR));
    }

    @Test
    @DisplayName("OPS-01: completada en booking pero con líneas sin terminar: por cerrar desde la hora de fin")
    void completedBookingWithLinesLeftOpen() {
        assertEquals(Optional.of(Reason.NOT_FINISHED), FollowUpPolicy.of(booking("COMPLETED"), line(ExecutionStatus.IN_PROGRESS), END, HOUR));
        assertEquals(Optional.of(Reason.NOT_STARTED), FollowUpPolicy.of(booking("COMPLETED"), line(ExecutionStatus.PENDING), END, HOUR));
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("COMPLETED"), line(ExecutionStatus.IN_PROGRESS), END.minusSeconds(60), HOUR));
        assertEquals(Optional.empty(), FollowUpPolicy.of(booking("COMPLETED"), List.of(), END.plus(HOUR), HOUR));
    }
}
