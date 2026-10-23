package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.TeamSummary;
import com.lavarapido.operations.domain.service.TeamSummaryCalculator.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Resumen del equipo de operarios")
class TeamSummaryCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private static OperatorWeekMetrics week(int completed, int previous) {
        return new OperatorWeekMetrics(MONDAY, MONDAY.plusDays(6), completed, previous, null, null, "NO_DATA",
                BigDecimal.ZERO, 0, 0, null);
    }

    @Test
    @DisplayName("suma los servicios de la semana, la tendencia y el promedio ponderado de calificaciones")
    void sumsTheTeam() {
        TeamSummary summary = TeamSummaryCalculator.compute(MONDAY, List.of(
                new Member(true, true, false, week(4, 2), 5.0, 3),     // disponible hoy
                new Member(true, true, true, week(2, 1), 4.0, 1),      // con ausencia hoy
                new Member(true, false, false, week(0, 1), null, 0),   // sin turno hoy
                new Member(false, true, false, week(0, 0), null, 0))); // inactivo

        assertEquals(LocalDate.of(2026, 10, 18), summary.weekEnd());
        assertEquals(4, summary.totalOperators());
        assertEquals(3, summary.activeOperators());
        assertEquals(1, summary.availableToday());
        assertEquals(1, summary.absentToday());
        assertEquals(6, summary.completedServices());
        assertEquals(4, summary.previousWeekServices());
        assertEquals(50, summary.servicesTrendPercent());
        assertEquals(4.8, summary.averageRating());          // (5*3 + 4*1) / 4 = 4.75
        assertEquals(4, summary.ratingsCount());
    }

    @Test
    @DisplayName("sin operarios ni datos no inventa tendencia ni calificación")
    void emptyTeam() {
        TeamSummary summary = TeamSummaryCalculator.compute(MONDAY, List.of());

        assertEquals(0, summary.totalOperators());
        assertEquals(0, summary.completedServices());
        assertNull(summary.servicesTrendPercent());
        assertNull(summary.averageRating());
    }
}
