package com.lavarapido.operations.domain.model;

import java.time.LocalDate;

/**
 * Resumen del equipo de operarios para la lista del administrador. Lo calcula TeamSummaryCalculator
 * con las mismas reglas de las métricas de cada operario (OperatorMetricsCalculator).
 *
 * @param totalOperators        cuentas con rol OPERATOR
 * @param activeOperators       operarios activos (operario y cuenta)
 * @param availableToday        activos, con turno hoy y sin ausencia que toque el día
 * @param absentToday           activos con una ausencia que toca el día de hoy
 * @param completedServices     reservas que el equipo terminó en la semana
 * @param previousWeekServices  reservas que el equipo terminó la semana anterior
 * @param servicesTrendPercent  cambio contra la semana anterior; null si la anterior fue 0
 * @param averageRating         promedio de todas las calificaciones; null si no hay ninguna
 * @param ratingsCount          calificaciones recibidas por el equipo
 */
public record TeamSummary(LocalDate weekStart, LocalDate weekEnd, int totalOperators, int activeOperators,
                          int availableToday, int absentToday, int completedServices, int previousWeekServices,
                          Integer servicesTrendPercent, Double averageRating, int ratingsCount) {
}
