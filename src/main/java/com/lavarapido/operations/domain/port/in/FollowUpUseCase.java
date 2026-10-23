package com.lavarapido.operations.domain.port.in;

import com.lavarapido.operations.domain.service.FollowUpPolicy.Reason;

import java.time.LocalDate;
import java.util.List;

/**
 * Reservas que terminaron y nadie cerró (FollowUpPolicy): las que nunca tuvieron operario se cierran
 * solas como "no asistió"; las que tenían operario quedan "Por cerrar" para el administrador, y se
 * avisa una vez al operario y a los administradores.
 */
public interface FollowUpUseCase {

    /** Una reserva "Por cerrar": NOT_STARTED o NOT_FINISHED, con el operario que la tenía. */
    record ToCloseView(long bookingId, Reason reason, int operatorId) {
    }

    /** Lo que hizo una vuelta de la tarea programada. */
    record FollowUpRun(int closedAsNotAttended, int noticesSent) {
    }

    /** Reservas por cerrar que empiezan entre from y to (sin fechas: la última semana hasta hoy). */
    List<ToCloseView> toClose(LocalDate from, LocalDate to);

    /**
     * El administrador confirma que se atendió: las líneas quedan terminadas (sin inventar horas) y la
     * reserva COMPLETED. 409 BOOKING_NOT_TO_CLOSE si no está por cerrar.
     */
    void closeAsAttended(long bookingId, long adminId);

    /** La tarea programada: cierra las que no tuvieron operario y avisa las que quedan por cerrar. */
    FollowUpRun followUp();
}
