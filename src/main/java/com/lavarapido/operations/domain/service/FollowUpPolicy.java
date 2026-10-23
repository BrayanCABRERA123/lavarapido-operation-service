package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.ServiceExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Qué hacer con una reserva que ya terminó y nadie cerró. El sistema solo decide solo cuando está
 * seguro:
 * <ul>
 *   <li>NO_OPERATOR: confirmada, sin operario, y ya pasó la hora de fin más el margen. Nadie pudo
 *   atenderla, así que queda "no asistió".</li>
 *   <li>NOT_STARTED: confirmada, con operario, terminó la hora y nadie le dio "Iniciar". Pudo
 *   atenderse sin registrarse: la resuelve el administrador ("Por cerrar").</li>
 *   <li>NOT_FINISHED: se inició, pasó la hora de fin más el margen y nadie le dio "Finalizar".
 *   También la resuelve el administrador.</li>
 * </ul>
 */
public final class FollowUpPolicy {

    public enum Reason { NO_OPERATOR, NOT_STARTED, NOT_FINISHED }

    private FollowUpPolicy() {
    }

    /** lines son las ejecuciones de esa reserva (vacío: nunca tuvo operario). */
    public static Optional<Reason> of(BookingSnapshot booking, List<ServiceExecution> lines, Instant now,
                                      Duration grace) {
        Instant end = booking.scheduledEnd();
        if (end == null) {
            return Optional.empty();
        }
        boolean pastGrace = !now.isBefore(end.plus(grace));
        return switch (booking.status()) {
            case "CONFIRMED" -> {
                if (lines.isEmpty()) {
                    yield pastGrace ? Optional.of(Reason.NO_OPERATOR) : Optional.empty();
                }
                boolean noneStarted = lines.stream().allMatch(line -> line.status() == ExecutionStatus.PENDING);
                yield noneStarted && !now.isBefore(end) ? Optional.of(Reason.NOT_STARTED) : Optional.empty();
            }
            case "IN_PROGRESS" -> {
                boolean unfinished = lines.stream().anyMatch(line -> line.status() != ExecutionStatus.COMPLETED);
                yield !lines.isEmpty() && unfinished && pastGrace ? Optional.of(Reason.NOT_FINISHED) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }
}
