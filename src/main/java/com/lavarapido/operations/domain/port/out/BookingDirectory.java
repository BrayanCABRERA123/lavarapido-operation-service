package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.BookingSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Puerto hacia booking-service (REST con el token del usuario que llama, ADR-004). Booking aplica
 * sus reglas: el cliente solo ve sus reservas, /admin solo el admin, /operator el operario.
 */
public interface BookingDirectory {

    Optional<BookingSnapshot> forAdmin(long bookingId);

    List<BookingSnapshot> forAdmin(LocalDate from, LocalDate to);

    Optional<BookingSnapshot> forOperator(long bookingId);

    List<BookingSnapshot> forOperator(LocalDate from, LocalDate to);

    Optional<BookingSnapshot> forCustomer(long bookingId);

    List<BookingSnapshot> mine();

    /**
     * Por la llave interna, sin usuario detrás: para asignar solo cuando se confirma una reserva
     * (RF-008) y para mostrarle al cliente quién está libre (RF-010). Sin los datos del vehículo.
     */
    Optional<BookingSnapshot> forService(long bookingId);

    List<BookingSnapshot> forService(LocalDate from, LocalDate to);

    /** El operario mueve la reserva: IN_PROGRESS al empezar, COMPLETED al terminar. */
    void advance(long bookingId, String status);
}
