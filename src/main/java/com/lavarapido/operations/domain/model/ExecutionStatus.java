package com.lavarapido.operations.domain.model;

import java.util.Arrays;

/**
 * Estado de la ejecucion de una linea de la reserva (execution.execution_status, seed 105).
 * Los ids son fijos; distinto del estado de la reserva (models.md, execution_status).
 */
public enum ExecutionStatus {

    PENDING(1),
    IN_PROGRESS(2),
    PAUSED(3),
    COMPLETED(4),
    WITH_ISSUE(5);

    private final short id;

    ExecutionStatus(int id) {
        this.id = (short) id;
    }

    public short id() {
        return id;
    }

    public static ExecutionStatus ofId(short id) {
        return Arrays.stream(values())
                .filter(status -> status.id == id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown execution status id " + id));
    }
}
