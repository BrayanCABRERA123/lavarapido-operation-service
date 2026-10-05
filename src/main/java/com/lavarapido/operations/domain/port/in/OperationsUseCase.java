package com.lavarapido.operations.domain.port.in;

import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.WeeklyAvailability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Casos de uso del operations-service. El id de quien llama sale del token (userId); el rol ya
 * lo exigio SecurityConfig segun la ruta (/admin, /operator, /ratings).
 */
public interface OperationsUseCase {

    // ------------------------------------------------------------------ vistas

    /** Operario con sus datos de cuenta (security), turno y resumen de calificaciones. */
    record OperatorView(int operatorId, long userId, String fullName, String email, String phone, boolean active,
                        LocalDate hiredOn, List<WeeklyAvailability> week, int completedServices,
                        Double averageRating, int ratingsCount) {
    }

    /** Quien tiene asignada una reserva y como va (estado de sus lineas). */
    record AssignmentView(long bookingId, int operatorId, String operatorName, ExecutionStatus status) {
    }

    /** Servicio de un operario: la reserva y el estado de su ejecucion. */
    record OperatorServiceView(BookingSnapshot booking, ExecutionStatus status, Instant startedAt,
                               Instant finishedAt, Short rating, String comment) {
    }

    /** Calificacion de una reserva (todas sus lineas tienen la misma). */
    record RatingView(long bookingId, String bookingCode, LocalDate date, String services, String vehicle,
                      String plate, short rating, String comment, Instant ratedAt, String operatorName) {
    }

    // ------------------------------------------------------------------ admin

    List<OperatorView> operators();

    OperatorView setActive(int operatorId, boolean active, long adminId);

    OperatorView setAvailability(int operatorId, List<WeeklyAvailability> week, long adminId);

    List<Absence> absences(int operatorId, LocalDate from, LocalDate to);

    /** Fechas y horas en la hora del lavadero. */
    Absence addAbsence(int operatorId, LocalDateTime startsAt, LocalDateTime endsAt, String reason, long adminId);

    void removeAbsence(int operatorId, int absenceId, long adminId);

    /** Asigna (o cambia) el operario de todas las lineas de la reserva (RF-008/009). */
    AssignmentView assign(long bookingId, int operatorId, long adminId);

    /** Asignaciones de las reservas que empiezan entre from y to. */
    List<AssignmentView> assignments(LocalDate from, LocalDate to);

    // ------------------------------------------------------------------ operario

    List<OperatorServiceView> myServices(LocalDate from, LocalDate to, long operatorUserId);

    OperatorServiceView start(long bookingId, long operatorUserId);

    OperatorServiceView finish(long bookingId, long operatorUserId);

    List<RatingView> myRatings(long operatorUserId);

    // ------------------------------------------------------------------ cliente

    /** Calificacion del cliente a una reserva terminada (RF-012), una sola vez. */
    RatingView rate(long bookingId, int rating, String comment, long customerUserId);

    /** Calificaciones que el cliente ya dio a sus reservas. */
    List<RatingView> myGivenRatings(long customerUserId);
}
