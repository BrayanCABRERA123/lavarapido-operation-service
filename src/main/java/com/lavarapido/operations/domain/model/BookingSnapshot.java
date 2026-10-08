package com.lavarapido.operations.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Lo que operations necesita de una reserva; el dueno es booking-service. Fechas y horas en la
 * hora del lavadero; scheduledStart/End en UTC.
 */
public record BookingSnapshot(long id, String code, String status, LocalDate date, LocalTime startTime,
                              LocalTime endTime, Instant scheduledStart, Instant scheduledEnd, Long ownerUserId,
                              String vehicle, String plate, BigDecimal total, List<Line> lines, Bay bay) {

    /** Una linea de la reserva: lineId es booking_service_id. */
    public record Line(long lineId, String serviceName) {
    }

    /** La bahia de la reserva (booking.service_bay_id); null si todavia no se asigno ninguna. */
    public record Bay(short id, String code, String name) {
    }

    public List<Long> lineIds() {
        return lines.stream().map(Line::lineId).toList();
    }

    public String servicesLabel() {
        return String.join(", ", lines.stream().map(Line::serviceName).toList());
    }

    public boolean overlaps(BookingSnapshot other) {
        return scheduledStart.isBefore(other.scheduledEnd) && other.scheduledStart.isBefore(scheduledEnd);
    }
}
