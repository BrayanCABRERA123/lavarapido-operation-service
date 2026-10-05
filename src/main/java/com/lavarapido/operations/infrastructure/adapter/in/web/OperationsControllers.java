package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.AbsenceRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.AbsenceResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.ActiveRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.AssignRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.AssignmentResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.AvailabilityRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.OperatorResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.OperatorServiceResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.RatingRequest;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.RatingResponse;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.ShiftRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Controladores del operations-service: admin, operario y cliente (roles en SecurityConfig). */
final class OperationsControllers {

    private OperationsControllers() {
    }

    /** Operarios, turnos, ausencias y asignacion. Solo ADMIN. */
    @RestController
    @RequestMapping("/api/v1/admin")
    static class AdminOperationsController {

        private final OperationsUseCase operations;

        AdminOperationsController(OperationsUseCase operations) {
            this.operations = operations;
        }

        /** Cuentas con rol OPERATOR (security) con su turno y calificacion promedio. */
        @GetMapping("/operators")
        List<OperatorResponse> operators() {
            return operations.operators().stream().map(OperatorResponse::from).toList();
        }

        @PatchMapping("/operators/{id}/status")
        OperatorResponse setActive(@AuthenticationPrincipal Jwt jwt, @PathVariable int id,
                                   @Valid @RequestBody ActiveRequest request) {
            return OperatorResponse.from(operations.setActive(id, request.active(), AuthenticatedUser.userId(jwt)));
        }

        /** Reemplaza el turno semanal completo (los dias que no vienen quedan libres). */
        @PutMapping("/operators/{id}/availability")
        OperatorResponse setAvailability(@AuthenticationPrincipal Jwt jwt, @PathVariable int id,
                                         @Valid @RequestBody AvailabilityRequest request) {
            return OperatorResponse.from(operations.setAvailability(id,
                    request.week().stream().map(ShiftRequest::toDomain).toList(), AuthenticatedUser.userId(jwt)));
        }

        @GetMapping("/operators/{id}/absences")
        List<AbsenceResponse> absences(@PathVariable int id, @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to) {
            return operations.absences(id, from, to).stream().map(AbsenceResponse::from).toList();
        }

        @PostMapping("/operators/{id}/absences")
        @ResponseStatus(HttpStatus.CREATED)
        AbsenceResponse addAbsence(@AuthenticationPrincipal Jwt jwt, @PathVariable int id,
                                   @Valid @RequestBody AbsenceRequest request) {
            return AbsenceResponse.from(operations.addAbsence(id, request.startsAt(), request.endsAt(),
                    request.reason(), AuthenticatedUser.userId(jwt)));
        }

        @DeleteMapping("/operators/{id}/absences/{absenceId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void removeAbsence(@AuthenticationPrincipal Jwt jwt, @PathVariable int id, @PathVariable int absenceId) {
            operations.removeAbsence(id, absenceId, AuthenticatedUser.userId(jwt));
        }

        /** Asigna (o cambia) el operario de una reserva (RF-008/009). */
        @PutMapping("/assignments/{bookingId}")
        AssignmentResponse assign(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                                  @Valid @RequestBody AssignRequest request) {
            return AssignmentResponse.from(operations.assign(bookingId, request.operatorId(), AuthenticatedUser.userId(jwt)));
        }

        /** Quien tiene cada reserva que empieza entre from y to (sin fechas: hoy). */
        @GetMapping("/assignments")
        List<AssignmentResponse> assignments(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
            return operations.assignments(from, to).stream().map(AssignmentResponse::from).toList();
        }
    }

    /** Servicios del operario que llama. Solo OPERATOR. */
    @RestController
    @RequestMapping("/api/v1/operator")
    static class OperatorController {

        private final OperationsUseCase operations;

        OperatorController(OperationsUseCase operations) {
            this.operations = operations;
        }

        /** Sus servicios entre from y to (sin fechas: hoy). */
        @GetMapping("/services")
        List<OperatorServiceResponse> services(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to) {
            return operations.myServices(from, to, AuthenticatedUser.userId(jwt)).stream()
                    .map(OperatorServiceResponse::from).toList();
        }

        @PostMapping("/services/{bookingId}/start")
        OperatorServiceResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return OperatorServiceResponse.from(operations.start(bookingId, AuthenticatedUser.userId(jwt)));
        }

        @PostMapping("/services/{bookingId}/finish")
        OperatorServiceResponse finish(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return OperatorServiceResponse.from(operations.finish(bookingId, AuthenticatedUser.userId(jwt)));
        }

        @GetMapping("/ratings")
        List<RatingResponse> ratings(@AuthenticationPrincipal Jwt jwt) {
            return operations.myRatings(AuthenticatedUser.userId(jwt)).stream().map(RatingResponse::from).toList();
        }
    }

    /** Calificaciones del cliente (RF-012). Solo CLIENT. */
    @RestController
    @RequestMapping("/api/v1/ratings")
    static class RatingController {

        private final OperationsUseCase operations;

        RatingController(OperationsUseCase operations) {
            this.operations = operations;
        }

        @GetMapping("/me")
        List<RatingResponse> mine(@AuthenticationPrincipal Jwt jwt) {
            return operations.myGivenRatings(AuthenticatedUser.userId(jwt)).stream().map(RatingResponse::from).toList();
        }

        @PostMapping("/{bookingId}")
        @ResponseStatus(HttpStatus.CREATED)
        RatingResponse rate(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
                            @Valid @RequestBody RatingRequest request) {
            return RatingResponse.from(operations.rate(bookingId, request.rating(), request.comment(),
                    AuthenticatedUser.userId(jwt)));
        }
    }
}
