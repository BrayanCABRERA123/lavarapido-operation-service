package com.lavarapido.operations.infrastructure.adapter.in.web.dto;

import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.BookingSnapshot;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.AssignmentView;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.CandidateView;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.OperatorServiceView;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.OperatorView;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.RatingView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Cuerpos de entrada y salida de la API del operations-service. */
public final class OperationsDtos {

    private OperationsDtos() {
    }

    // ------------------------------------------------------------------ entrada

    public record ShiftRequest(@NotNull @Min(1) @Max(7) Short dayOfWeek, @NotNull LocalTime startsAt,
                               @NotNull LocalTime endsAt) {

        public WeeklyAvailability toDomain() {
            return new WeeklyAvailability(dayOfWeek, startsAt, endsAt);
        }
    }

    public record AvailabilityRequest(@NotNull @Valid List<ShiftRequest> week) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }

    /** Fechas y horas en la hora del lavadero. */
    public record AbsenceRequest(@NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt,
                                 @NotBlank @Size(max = 120) String reason) {
    }

    public record AssignRequest(@NotNull Integer operatorId) {
    }

    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 500) String comment) {
    }

    // ------------------------------------------------------------------ salida

    public record ShiftResponse(short dayOfWeek, LocalTime startsAt, LocalTime endsAt) {

        static ShiftResponse from(WeeklyAvailability shift) {
            return new ShiftResponse(shift.dayOfWeek(), shift.startsAt(), shift.endsAt());
        }
    }

    public record OperatorResponse(int id, long userId, String fullName, String email, String phone, boolean active,
                                   LocalDate hiredOn, List<ShiftResponse> week, int completedServices,
                                   Double averageRating, int ratingsCount) {

        public static OperatorResponse from(OperatorView view) {
            return new OperatorResponse(view.operatorId(), view.userId(), view.fullName(), view.email(), view.phone(),
                    view.active(), view.hiredOn(), view.week().stream().map(ShiftResponse::from).toList(),
                    view.completedServices(), view.averageRating(), view.ratingsCount());
        }
    }

    public record AbsenceResponse(int id, Instant startsAt, Instant endsAt, String reason) {

        public static AbsenceResponse from(Absence absence) {
            return new AbsenceResponse(absence.id(), absence.startsAt(), absence.endsAt(), absence.reason());
        }
    }

    /** La bahia de esa reserva; null si todavia no se asigno ninguna. */
    public record BayResponse(short id, String code, String name) {

        public static BayResponse from(BookingSnapshot.Bay bay) {
            return bay == null ? null : new BayResponse(bay.id(), bay.code(), bay.name());
        }
    }

    public record AssignmentResponse(long bookingId, int operatorId, String operatorName, String status,
                                     BayResponse bay) {

        public static AssignmentResponse from(AssignmentView view) {
            return new AssignmentResponse(view.bookingId(), view.operatorId(), view.operatorName(),
                    view.status().name(), BayResponse.from(view.bay()));
        }
    }

    /** Operario candidato para una reserva: available false trae el codigo del motivo. */
    public record CandidateResponse(int operatorId, String fullName, Double averageRating, int ratingsCount,
                                    boolean assigned, boolean available, String unavailableReason) {

        public static CandidateResponse from(CandidateView view) {
            return new CandidateResponse(view.operatorId(), view.fullName(), view.averageRating(), view.ratingsCount(),
                    view.assigned(), view.unavailableReason() == null, view.unavailableReason());
        }
    }

    /** Reserva vista por el operario, con el estado de su ejecucion. */
    public record OperatorServiceResponse(long bookingId, String code, String bookingStatus, LocalDate date,
                                          LocalTime startTime, LocalTime endTime, String services, String vehicle,
                                          String plate, BigDecimal total, String status, Instant startedAt,
                                          Instant finishedAt, Short rating, String comment) {

        public static OperatorServiceResponse from(OperatorServiceView view) {
            BookingSnapshot b = view.booking();
            return new OperatorServiceResponse(b.id(), b.code(), b.status(), b.date(), b.startTime(), b.endTime(),
                    b.servicesLabel(), b.vehicle(), b.plate(), b.total(), view.status().name(), view.startedAt(),
                    view.finishedAt(), view.rating(), view.comment());
        }
    }

    public record RatingResponse(long bookingId, String bookingCode, LocalDate date, String services, String vehicle,
                                 String plate, short rating, String comment, Instant ratedAt, String operatorName) {

        public static RatingResponse from(RatingView view) {
            return new RatingResponse(view.bookingId(), view.bookingCode(), view.date(), view.services(), view.vehicle(),
                    view.plate(), view.rating(), view.comment(), view.ratedAt(), view.operatorName());
        }
    }
}
