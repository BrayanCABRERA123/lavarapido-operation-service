package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Métricas semanales del operario")
class OperatorMetricsCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final int OPERATOR = 3;

    // reserva de un servicio a las 9:00 de Bogotá (14:00Z) del día indicado
    private static BookingSnapshot booking(long id, LocalDate date, String total) {
        Instant start = Instant.parse(date + "T14:00:00Z");
        return new BookingSnapshot(id, "RES-" + id, "COMPLETED", date, LocalTime.of(9, 0), LocalTime.of(10, 0),
                start, start.plusSeconds(3600), 7L, "Mazda 3", "ABC-123", new BigDecimal(total),
                List.of(new BookingSnapshot.Line(id * 10, "Premium")), null);
    }

    private static ServiceExecution execution(long bookingId, ExecutionStatus status, String startedAt, String finishedAt) {
        return ServiceExecution.restore(bookingId, bookingId * 10, OPERATOR, status,
                startedAt == null ? null : Instant.parse(startedAt), finishedAt == null ? null : Instant.parse(finishedAt),
                null, null, null, true);
    }

    private static final List<WeeklyAvailability> MONDAY_TO_FRIDAY_8_TO_17 = List.of(1, 2, 3, 4, 5).stream()
            .map(day -> new WeeklyAvailability(day.shortValue(), LocalTime.of(8, 0), LocalTime.of(17, 0))).toList();

    @Test
    @DisplayName("cuenta servicios, tendencia, puntualidad, ingresos y horas de la semana")
    void computesTheWeek() {
        List<BookingSnapshot> bookings = List.of(
                booking(1, MONDAY.plusDays(1), "35000"),   // a tiempo (5 min después) y terminada
                booking(2, MONDAY.plusDays(2), "40000"),   // tarde (30 min después) y terminada
                booking(3, MONDAY.plusDays(3), "50000"),   // a tiempo, todavía en curso
                booking(4, MONDAY.minusDays(5), "20000")); // semana anterior, terminada
        List<ServiceExecution> executions = List.of(
                execution(1, ExecutionStatus.COMPLETED, "2026-10-06T14:05:00Z", "2026-10-06T15:00:00Z"),
                execution(2, ExecutionStatus.COMPLETED, "2026-10-07T14:30:00Z", "2026-10-07T15:30:00Z"),
                execution(3, ExecutionStatus.IN_PROGRESS, "2026-10-08T14:00:00Z", null),
                execution(4, ExecutionStatus.COMPLETED, "2026-09-30T14:00:00Z", "2026-09-30T15:00:00Z"));

        OperatorWeekMetrics metrics = OperatorMetricsCalculator.compute(MONDAY, bookings, executions,
                MONDAY_TO_FRIDAY_8_TO_17);

        assertEquals(LocalDate.of(2026, 10, 11), metrics.weekEnd());
        assertEquals(2, metrics.completedServices());
        assertEquals(1, metrics.previousWeekServices());
        assertEquals(100, metrics.servicesTrendPercent());
        assertEquals(67, metrics.punctualityPercent());   // 2 de 3 iniciados a tiempo
        assertEquals("NEEDS_IMPROVEMENT", metrics.punctualityLevel());
        assertEquals(new BigDecimal("75000"), metrics.revenue());
        assertEquals(1.9, metrics.workedHours());          // 55 + 60 minutos
        assertEquals(45.0, metrics.availableHours());
        assertEquals(4, metrics.occupancyPercent());
    }

    @Test
    @DisplayName("sin servicios ni turno no inventa datos: tendencia, puntualidad y ocupación quedan vacías")
    void withoutDataEverythingIsEmpty() {
        OperatorWeekMetrics metrics = OperatorMetricsCalculator.compute(MONDAY, List.of(), List.of(), List.of());

        assertEquals(0, metrics.completedServices());
        assertNull(metrics.servicesTrendPercent());
        assertNull(metrics.punctualityPercent());
        assertEquals("NO_DATA", metrics.punctualityLevel());
        assertEquals(BigDecimal.ZERO, metrics.revenue());
        assertNull(metrics.occupancyPercent());
    }

    @Test
    @DisplayName("iniciar hasta 10 minutos tarde es puntual; reservas de otro operario no cuentan")
    void toleranceAndOtherOperators() {
        List<BookingSnapshot> bookings = List.of(booking(5, MONDAY, "30000"), booking(6, MONDAY.plusDays(1), "30000"));
        List<ServiceExecution> executions = List.of(
                execution(5, ExecutionStatus.COMPLETED, "2026-10-05T14:10:00Z", "2026-10-05T15:00:00Z"));

        OperatorWeekMetrics metrics = OperatorMetricsCalculator.compute(MONDAY, bookings, executions,
                MONDAY_TO_FRIDAY_8_TO_17);

        assertEquals(1, metrics.completedServices());
        assertEquals(100, metrics.punctualityPercent());
        assertEquals("EXCELLENT", metrics.punctualityLevel());
    }
}
