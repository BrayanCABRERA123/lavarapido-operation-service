package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.TeamSummary;

import java.time.LocalDate;
import java.util.List;

/**
 * Resumen del equipo a partir de cada operario. Código de dominio puro: quién está disponible hoy
 * y cuántos servicios hizo el equipo en la semana se deciden aquí, no en las pantallas.
 */
public final class TeamSummaryCalculator {

    /**
     * Lo que se sabe de un operario para el resumen.
     *
     * @param onShiftToday tiene turno el día de la semana de hoy
     * @param absentToday  tiene una ausencia que toca el día de hoy
     * @param week         sus métricas de la semana (OperatorMetricsCalculator)
     */
    public record Member(boolean active, boolean onShiftToday, boolean absentToday, OperatorWeekMetrics week,
                         Double averageRating, int ratingsCount) {
    }

    private TeamSummaryCalculator() {
    }

    public static TeamSummary compute(LocalDate weekStart, List<Member> members) {
        int active = 0, available = 0, absent = 0, completed = 0, previous = 0, ratings = 0;
        double ratingSum = 0;
        for (Member member : members) {
            completed += member.week().completedServices();
            previous += member.week().previousWeekServices();
            if (member.averageRating() != null && member.ratingsCount() > 0) {
                ratings += member.ratingsCount();
                ratingSum += member.averageRating() * member.ratingsCount();
            }
            if (!member.active()) {
                continue;
            }
            active++;
            if (member.absentToday()) {
                absent++;
            } else if (member.onShiftToday()) {
                available++;
            }
        }
        Integer trend = previous == 0 ? null : Math.round((completed - previous) * 100f / previous);
        Double average = ratings == 0 ? null : Math.round(ratingSum / ratings * 10) / 10.0;
        return new TeamSummary(weekStart, weekStart.plusDays(6), members.size(), active, available, absent,
                completed, previous, trend, average, ratings);
    }
}
