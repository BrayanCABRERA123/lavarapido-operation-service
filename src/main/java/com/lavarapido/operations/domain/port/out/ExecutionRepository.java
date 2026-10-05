package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.ServiceExecution;

import java.util.Collection;
import java.util.List;

/** Ejecucion de cada linea de reserva (execution.service_execution). */
public interface ExecutionRepository {

    List<ServiceExecution> findByLineIds(Collection<Long> bookingServiceIds);

    List<ServiceExecution> findByOperator(int operatorId);

    /** Lineas calificadas de un operario (para su promedio). */
    List<ServiceExecution> findRated(int operatorId);

    List<ServiceExecution> saveAll(List<ServiceExecution> executions, long actor);
}
