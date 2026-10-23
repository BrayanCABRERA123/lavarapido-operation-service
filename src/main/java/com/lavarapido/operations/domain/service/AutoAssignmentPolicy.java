package com.lavarapido.operations.domain.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A quién se asigna una reserva sola (RF-008), entre los operarios que pueden tomarla según
 * AssignmentPolicy: el que eligió el cliente si está libre (RF-010); si no, el que tiene menos
 * reservas ese día, y entre iguales el de id más bajo para que el resultado sea siempre el mismo.
 */
public final class AutoAssignmentPolicy {

    /** Un operario que puede tomar la reserva y cuántas reservas ya tiene ese día. */
    public record Candidate(int operatorId, int bookingsThatDay) {
    }

    private AutoAssignmentPolicy() {
    }

    public static Optional<Integer> choose(List<Candidate> available, Integer preferredOperatorId) {
        if (preferredOperatorId != null
                && available.stream().anyMatch(c -> c.operatorId() == preferredOperatorId)) {
            return Optional.of(preferredOperatorId);
        }
        return available.stream()
                .min(Comparator.comparingInt(Candidate::bookingsThatDay).thenComparingInt(Candidate::operatorId))
                .map(Candidate::operatorId);
    }
}
