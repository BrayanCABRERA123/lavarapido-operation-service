package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.BookingSnapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * Desde cuándo el operario puede iniciar un servicio: hasta 15 minutos antes de la hora de la reserva
 * (por si el cliente llega temprano). Antes no tiene sentido: el servicio quedaría "iniciado" horas o
 * días antes de que llegue el carro y el cliente seguiría recibiendo sus recordatorios.
 */
public final class StartPolicy {

    public static final Duration EARLY_START = Duration.ofMinutes(15);

    private StartPolicy() {
    }

    /** Primer instante en que se puede iniciar; las pantallas habilitan el botón con este dato. */
    public static Instant startableAt(BookingSnapshot booking) {
        return booking.scheduledStart().minus(EARLY_START);
    }

    public static void check(BookingSnapshot booking, Instant now) {
        if (now.isBefore(startableAt(booking))) {
            throw new ConflictException("SERVICE_TOO_EARLY",
                    "The service can start from " + EARLY_START.toMinutes() + " minutes before the booking time");
        }
    }
}
