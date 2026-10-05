package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.WeeklyAvailability;

import java.util.List;

/**
 * Reglas para asignar un operario a una reserva (RF-008/009): que este activo, que tenga turno
 * ese dia a esa hora, que no este ausente y que no tenga otra reserva a la misma hora.
 * Solo dominio: los datos llegan ya cargados.
 */
public final class AssignmentPolicy {

    private AssignmentPolicy() {
    }

    /**
     * @param busyWith reservas que el operario ya tiene asignadas ese dia (sin la que se asigna)
     */
    public static void check(Operator operator, List<WeeklyAvailability> week, List<Absence> absences,
                             BookingSnapshot booking, List<BookingSnapshot> busyWith) {
        if (!operator.active()) {
            throw new ConflictException("OPERATOR_INACTIVE", "The operator is not active");
        }
        short day = (short) booking.date().getDayOfWeek().getValue();
        boolean onShift = week.stream()
                .anyMatch(shift -> shift.dayOfWeek() == day && shift.covers(booking.startTime(), booking.endTime()));
        if (!onShift) {
            throw new ConflictException("OPERATOR_NOT_ON_SHIFT", "The operator does not work at that time");
        }
        boolean absent = absences.stream()
                .anyMatch(absence -> absence.overlaps(booking.scheduledStart(), booking.scheduledEnd()));
        if (absent) {
            throw new ConflictException("OPERATOR_ABSENT", "The operator is absent at that time");
        }
        boolean busy = busyWith.stream().anyMatch(other -> other.id() != booking.id() && other.overlaps(booking));
        if (busy) {
            throw new ConflictException("OPERATOR_BUSY", "The operator already has a booking at that time");
        }
    }
}
