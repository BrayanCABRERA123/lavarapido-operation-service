package com.lavarapido.operations.domain.model;

import com.lavarapido.operations.domain.exception.InvalidValueException;

import java.time.Instant;
import java.util.Objects;

/**
 * Ausencia puntual de un operario (execution.operator_absence): incapacidad, permiso,
 * vacaciones. Instantes en UTC. La base tambien impide dos ausencias traslapadas
 * (tr_operator_absence_overlap).
 */
public record Absence(Integer id, Instant startsAt, Instant endsAt, String reason) {

    private static final int MAX_REASON = 120;

    public Absence {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidValueException("INVALID_RANGE", "The absence must end after it starts");
        }
        reason = reason == null ? "" : reason.trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw new InvalidValueException("INVALID_REASON", "The reason is required (max " + MAX_REASON + ")");
        }
    }

    public boolean overlaps(Instant start, Instant end) {
        return startsAt.isBefore(end) && start.isBefore(endsAt);
    }
}
