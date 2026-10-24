package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.model.BookingSnapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * Cuándo el operario puede iniciar un servicio: desde 15 minutos antes de la hora de la reserva (por si
 * el cliente llega temprano) y hasta un límite después de la hora de fin (A-12, app.start.late-limit,
 * 4 horas por defecto). Antes no tiene sentido: el servicio quedaría "iniciado" horas o días antes de
 * que llegue el carro y el cliente seguiría recibiendo sus recordatorios. Después tampoco: una reserva
 * de ayer que nadie inició ya no se "inicia", la cierra el administrador desde "Por cerrar".
 */
public final class StartPolicy {

    public static final Duration EARLY_START = Duration.ofMinutes(15);
    /** Límite posterior por defecto (START_LATE_LIMIT): el mismo turno de trabajo. */
    public static final Duration DEFAULT_LATE_LIMIT = Duration.ofHours(4);

    private StartPolicy() {
    }

    /** Primer instante en que se puede iniciar; las pantallas habilitan el botón con este dato. */
    public static Instant startableAt(BookingSnapshot booking) {
        return booking.scheduledStart().minus(EARLY_START);
    }

    /** Último instante en que se puede iniciar: la hora de fin más el límite (A-12). */
    public static Instant startableUntil(BookingSnapshot booking, Duration lateLimit) {
        return booking.scheduledEnd().plus(lateLimit);
    }

    public static void check(BookingSnapshot booking, Instant now, Duration lateLimit) {
        if (now.isBefore(startableAt(booking))) {
            throw new ConflictException("SERVICE_TOO_EARLY",
                    "The service can start from " + EARLY_START.toMinutes() + " minutes before the booking time");
        }
        if (now.isAfter(startableUntil(booking, lateLimit))) {
            throw new ConflictException("SERVICE_TOO_LATE", "The service could start until "
                    + lateLimit.toMinutes() + " minutes after the booking end; the admin closes it from To close");
        }
    }
}
