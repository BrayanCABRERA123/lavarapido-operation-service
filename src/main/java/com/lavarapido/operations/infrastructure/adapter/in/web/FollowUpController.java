package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.port.in.FollowUpUseCase;
import com.lavarapido.operations.infrastructure.adapter.in.web.dto.OperationsDtos.ToCloseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Reservas "Por cerrar": terminaron con operario y nadie las registró. Solo ADMIN. El "no asistió"
 * lo marca el admin en booking-service (PATCH /admin/bookings/{id}/status); aquí se confirma que sí
 * se atendió.
 */
@RestController
@RequestMapping("/api/v1/admin/assignments")
class FollowUpController {

    private final FollowUpUseCase followUp;

    FollowUpController(FollowUpUseCase followUp) {
        this.followUp = followUp;
    }

    /** Por cerrar entre from y to (sin fechas: los últimos app.follow-up.look-back-days días hasta hoy). */
    @GetMapping("/to-close")
    List<ToCloseResponse> toClose(@RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to) {
        return followUp.toClose(from, to).stream().map(ToCloseResponse::from).toList();
    }

    /** Se atendió aunque no se registró: la reserva queda completada con su operario. */
    @PostMapping("/{bookingId}/attended")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void attended(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        followUp.closeAsAttended(bookingId, AuthenticatedUser.userId(jwt));
    }
}
