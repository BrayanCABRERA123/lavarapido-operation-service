package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.ServiceExecution;

import java.util.Collection;
import java.util.List;

/** Ejecucion de cada linea de reserva (execution.service_execution). */
public interface ExecutionRepository {

    /**
     * Bloquea las asignaciones hasta que termine la transaccion: si el admin asigna a mano justo
     * cuando la asignacion automatica elige al mismo operario, se atienden una detras de la otra y
     * la segunda ya ve la primera (no quedan dos servicios a la misma hora).
     */
    void lockAssignments();

    List<ServiceExecution> findByLineIds(Collection<Long> bookingServiceIds);

    /**
     * Las mismas ejecuciones, bloqueadas hasta que termine la transacción (OPS-03). Quien llega
     * después espera y lee lo que dejó el primero: así no se pisan dos cambios a la misma reserva (por
     * ejemplo, el admin reasigna justo cuando el operario inicia, o el cliente califica dos veces).
     */
    List<ServiceExecution> lockByLineIds(Collection<Long> bookingServiceIds);

    List<ServiceExecution> findByOperator(int operatorId);

    /** Lineas calificadas de un operario (para su promedio). */
    List<ServiceExecution> findRated(int operatorId);

    List<ServiceExecution> saveAll(List<ServiceExecution> executions, long actor);

    /**
     * E-12: quita la asignación de una reserva que nadie puede atender a su hora nueva. Solo
     * ejecuciones PENDING (sin historia); se borran de verdad porque booking_service_id es único y la
     * reserva se debe poder asignar otra vez.
     */
    void deletePending(List<ServiceExecution> executions);

    /**
     * Escribe en la base lo guardado en esta transacción (sin confirmarla). Se usa antes de llamar a
     * booking-service: si la base rechaza el cambio, la reserva no se mueve (OPS-01).
     */
    void flush();
}
