package com.lavarapido.operations.domain.port.in;

import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.OperatorWeekMetrics;
import com.lavarapido.operations.domain.model.TeamSummary;
import com.lavarapido.operations.domain.model.WeeklyAvailability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

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

    /**
     * Quien tiene asignada una reserva y como va (estado de sus lineas). bay es la bahia de ESA
     * reserva (booking.service_bay_id): el operario no esta fijo a una bahia (ADR-010), la pareja
     * cambia reserva por reserva; esto es lo que permite ver "quien esta en cual bahia ahora".
     */
    record AssignmentView(long bookingId, int operatorId, String operatorName, ExecutionStatus status,
                          BookingSnapshot.Bay bay, String notes) {
    }

    /**
     * Operario visto como candidato para una reserva: si se le puede asignar y, si no, el codigo
     * del motivo (OPERATOR_INACTIVE, OPERATOR_NOT_ON_SHIFT, OPERATOR_ABSENT, OPERATOR_BUSY).
     */
    record CandidateView(int operatorId, String fullName, Double averageRating, int ratingsCount,
                         boolean assigned, String unavailableReason) {
    }

    /** Servicio de un operario: la reserva y el estado de su ejecucion. */
    /** startableAt: desde cuándo se puede iniciar (StartPolicy, 15 minutos antes de la reserva). */
    /** clientName: primer nombre del cliente, como en las calificaciones (vacío si no se pudo leer). */
    /** startableUntil: hasta cuándo se puede iniciar (A-12, la hora de fin más app.start.late-limit). */
    record OperatorServiceView(BookingSnapshot booking, ExecutionStatus status, Instant startedAt,
                               Instant finishedAt, Short rating, String comment, Instant startableAt,
                               String clientName, Instant startableUntil) {
    }

    /** Calificacion de una reserva (todas sus lineas tienen la misma). */
    /** operatorName lo ve el cliente; clientName lo ve el operario (solo el primer nombre de cada uno). */
    /** El cliente solo ve el primer nombre de quien atiende su reserva. */
    record BookingOperatorView(long bookingId, String operatorName) {
    }

    record RatingView(long bookingId, String bookingCode, LocalDate date, String services, String vehicle,
                      String plate, short rating, String comment, Instant ratedAt, String operatorName,
                      String clientName) {
    }

    /** Operario libre para un horario, como lo ve el cliente al reservar (RF-010): solo el primer nombre. */
    /** Reservas que todavía necesitan operario, en orden de hora de inicio. */
    record UnassignedView(List<Long> bookingIds) {
        public int count() {
            return bookingIds.size();
        }
    }

    record AvailableOperatorView(int operatorId, String firstName, Double averageRating, int ratingsCount) {
    }

    // ------------------------------------------------------------------ automático (RF-008/010)

    /**
     * Asigna sola una reserva recién confirmada: al operario que eligió el cliente si está libre, o al
     * libre con menos reservas ese día. No hace nada si ya tiene operario, si no es asignable o si
     * nadie está libre (el administrador recibe la alerta de reserva sin operario).
     */
    Optional<AssignmentView> autoAssign(long bookingId, Long customerUserId, Integer preferredOperatorId);

    /** Operarios libres para ese horario (RF-010): activos, con turno, sin ausencia y sin otra reserva. */
    List<AvailableOperatorView> availableOperators(LocalDate date, LocalTime time, int minutes);

    // ------------------------------------------------------------------ admin

    List<OperatorView> operators();

    /** Métricas de la semana que contiene weekOf (por defecto la actual): servicios, puntualidad, ingresos y horas. */
    OperatorWeekMetrics metrics(int operatorId, LocalDate weekOf);

    /** Resumen del equipo: activos, disponibles hoy, servicios de la semana con su tendencia y calificación. */
    TeamSummary teamSummary(LocalDate weekOf);

    OperatorView setActive(int operatorId, boolean active, long adminId);

    OperatorView setAvailability(int operatorId, List<WeeklyAvailability> week, long adminId);

    List<Absence> absences(int operatorId, LocalDate from, LocalDate to);

    /** Fechas y horas en la hora del lavadero. */
    Absence addAbsence(int operatorId, LocalDateTime startsAt, LocalDateTime endsAt, String reason, long adminId);

    void removeAbsence(int operatorId, int absenceId, long adminId);

    /** Asigna (o cambia) el operario de todas las lineas de la reserva (RF-008/009). */
    /** notes: indicaciones del administrador para el operario (B-01, opcional, hasta 300 caracteres). */
    AssignmentView assign(long bookingId, int operatorId, long adminId, String notes);

    /** Todos los operarios con su disponibilidad real para esa reserva (mismas reglas que assign). */
    List<CandidateView> candidates(long bookingId);

    /** Asignaciones de las reservas que empiezan entre from y to. */
    List<AssignmentView> assignments(LocalDate from, LocalDate to);

    /**
     * La única regla de "sin asignar" que usan las pantallas: reservas que empiezan entre from y to,
     * programadas o confirmadas, que todavía no terminaron y no tienen operario en ninguna línea.
     */
    UnassignedView unassigned(LocalDate from, LocalDate to);

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

    /** Operario asignado a cada reserva del cliente que llama (solo las que ya tienen uno). */
    List<BookingOperatorView> myBookingOperators();
}
