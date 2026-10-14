package com.lavarapido.operations.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cómo le fue a un operario en una semana (lunes a domingo, hora del lavadero). Lo calcula
 * OperatorMetricsCalculator con las ejecuciones del operario y las reservas que atendió.
 *
 * @param completedServices     reservas que terminó en la semana
 * @param previousWeekServices  reservas que terminó la semana anterior
 * @param servicesTrendPercent  cambio contra la semana anterior; null si la anterior fue 0
 * @param punctualityPercent    % de servicios iniciados a tiempo; null si no inició ninguno
 * @param punctualityLevel      EXCELLENT (≥ 90), GOOD (≥ 70), NEEDS_IMPROVEMENT o NO_DATA
 * @param revenue               valor de las reservas que terminó en la semana
 * @param workedHours           horas entre iniciar y terminar sus servicios de la semana
 * @param availableHours        horas de su turno semanal
 * @param occupancyPercent      horas trabajadas sobre horas disponibles; null sin turno
 */
public record OperatorWeekMetrics(LocalDate weekStart, LocalDate weekEnd, int completedServices,
                                  int previousWeekServices, Integer servicesTrendPercent,
                                  Integer punctualityPercent, String punctualityLevel, BigDecimal revenue,
                                  double workedHours, double availableHours, Integer occupancyPercent) {
}
