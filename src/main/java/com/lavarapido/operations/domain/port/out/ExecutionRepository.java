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

    List<ServiceExecution> findByOperator(int operatorId);

    /** Lineas calificadas de un operario (para su promedio). */
    List<ServiceExecution> findRated(int operatorId);

    List<ServiceExecution> saveAll(List<ServiceExecution> executions, long actor);
}
