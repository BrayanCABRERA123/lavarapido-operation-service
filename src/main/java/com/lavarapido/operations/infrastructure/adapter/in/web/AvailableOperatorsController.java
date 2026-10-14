package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.port.in.OperationsUseCase;
import com.lavarapido.operations.domain.port.in.OperationsUseCase.AvailableOperatorView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * RF-010: el cliente ve qué operarios están libres para el horario que va a reservar y puede elegir
 * uno (booking-service lo pasa en el evento y operations lo asigna si sigue libre). Solo el primer
 * nombre y la calificación: nada de contacto.
 */
@RestController
class AvailableOperatorsController {

    private final OperationsUseCase operations;

    AvailableOperatorsController(OperationsUseCase operations) {
        this.operations = operations;
    }

    /** date=2026-10-20&time=09:00&minutes=90 */
    @GetMapping("/api/v1/operators/available")
    List<AvailableOperatorView> available(@RequestParam LocalDate date,
                                          @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime time,
                                          @RequestParam int minutes) {
        return operations.availableOperators(date, time, minutes);
    }
}
