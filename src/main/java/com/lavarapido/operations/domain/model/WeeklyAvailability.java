package com.lavarapido.operations.domain.model;

import com.lavarapido.operations.domain.exception.InvalidValueException;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Turno semanal de un operario (execution.operator_availability): un dia (1 = lunes ... 7 =
 * domingo, como business_hour) y una franja. Sin turno ese dia, no se le asigna nada.
 */
public record WeeklyAvailability(short dayOfWeek, LocalTime startsAt, LocalTime endsAt) {

    public WeeklyAvailability {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new InvalidValueException("INVALID_DAY", "Day of week must be 1 to 7");
        }
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidValueException("INVALID_RANGE", "The shift must end after it starts");
        }
    }

    /** La franja [start, end) del mismo dia cabe completa en el turno. */
    public boolean covers(LocalTime start, LocalTime end) {
        return !start.isBefore(startsAt) && !end.isAfter(endsAt);
    }
}
