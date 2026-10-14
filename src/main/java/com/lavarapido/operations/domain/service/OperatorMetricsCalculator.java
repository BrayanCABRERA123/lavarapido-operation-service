package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.model.WeeklyAvailability;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Métricas semanales de un operario. Código de dominio puro: recibe las reservas (de booking) y
 * las ejecuciones del operario, y no consulta nada más.
 *
 * Se cuenta por reserva, no por línea: una reserva con dos servicios es un solo servicio atendido,
 * se inicia y se termina una vez y su valor se suma una vez.
 */
public final class OperatorMetricsCalculator {

    /** Iniciar hasta 10 minutos después de la hora reservada cuenta como puntual. */
    public static final Duration PUNCTUALITY_TOLERANCE = Duration.ofMinutes(10);

    private OperatorMetricsCalculator() {
    }

    public static OperatorWeekMetrics compute(LocalDate weekStart, List<BookingSnapshot> bookings,
                                              List<ServiceExecution> operatorExecutions,
                                              List<WeeklyAvailability> availability) {
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate previousStart = weekStart.minusDays(7);

        Map<Long, ServiceExecution> byLine = new HashMap<>();
        operatorExecutions.forEach(execution -> byLine.put(execution.bookingServiceId(), execution));

        int completed = 0, previous = 0, started = 0, punctual = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        Duration worked = Duration.ZERO;
        for (BookingSnapshot booking : bookings) {
            List<ServiceExecution> mine = booking.lineIds().stream().map(byLine::get).filter(Objects::nonNull).toList();
            if (mine.isEmpty()) {
                continue;
            }
            boolean done = mine.stream().allMatch(e -> e.status() == ExecutionStatus.COMPLETED);
            LocalDate date = booking.date();
            if (!date.isBefore(previousStart) && date.isBefore(weekStart) && done) {
                previous++;
            }
            if (date.isBefore(weekStart) || date.isAfter(weekEnd)) {
                continue;
            }
            Instant startedAt = mine.stream().map(ServiceExecution::startedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            if (startedAt != null) {
                started++;
                if (!startedAt.isAfter(booking.scheduledStart().plus(PUNCTUALITY_TOLERANCE))) {
                    punctual++;
                }
            }
            if (done) {
                completed++;
                revenue = revenue.add(booking.total() == null ? BigDecimal.ZERO : booking.total());
                Instant finishedAt = mine.stream().map(ServiceExecution::finishedAt).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null);
                if (startedAt != null && finishedAt != null && finishedAt.isAfter(startedAt)) {
                    worked = worked.plus(Duration.between(startedAt, finishedAt));
                }
            }
        }

        Integer trend = previous == 0 ? null : Math.round((completed - previous) * 100f / previous);
        Integer punctuality = started == 0 ? null : Math.round(punctual * 100f / started);
        double workedHours = round1(worked.toMinutes() / 60.0);
        double availableHours = round1(availability.stream()
                .mapToLong(shift -> Duration.between(shift.startsAt(), shift.endsAt()).toMinutes()).sum() / 60.0);
        Integer occupancy = availableHours == 0 ? null : (int) Math.round(workedHours * 100 / availableHours);
        return new OperatorWeekMetrics(weekStart, weekEnd, completed, previous, trend, punctuality,
                levelOf(punctuality), revenue, workedHours, availableHours, occupancy);
    }

    private static String levelOf(Integer punctuality) {
        if (punctuality == null) {
            return "NO_DATA";
        }
        if (punctuality >= 90) {
            return "EXCELLENT";
        }
        return punctuality >= 70 ? "GOOD" : "NEEDS_IMPROVEMENT";
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
