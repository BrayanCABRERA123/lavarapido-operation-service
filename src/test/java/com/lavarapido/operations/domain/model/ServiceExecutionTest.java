package com.lavarapido.operations.domain.model;

import com.lavarapido.operations.domain.exception.ConflictException;
import com.lavarapido.operations.domain.exception.InvalidValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ServiceExecution (ejecucion y calificacion de una linea)")
class ServiceExecutionTest {

    private static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    @Test
    @DisplayName("una linea asignada queda pendiente, se empieza y se termina en orden")
    void lifecycle() {
        ServiceExecution execution = ServiceExecution.assign(10L, 3);
        assertEquals(ExecutionStatus.PENDING, execution.status());

        execution.start(NOW);
        assertEquals(ExecutionStatus.IN_PROGRESS, execution.status());
        assertEquals(NOW, execution.startedAt());

        execution.finish(NOW.plusSeconds(1800));
        assertEquals(ExecutionStatus.COMPLETED, execution.status());
    }

    @Test
    @DisplayName("no se termina sin empezar ni se reasigna despues de empezar")
    void guards() {
        ServiceExecution execution = ServiceExecution.assign(10L, 3);
        assertThrows(ConflictException.class, () -> execution.finish(NOW));

        execution.start(NOW);
        assertThrows(ConflictException.class, () -> execution.reassign(4));
        assertThrows(ConflictException.class, () -> execution.start(NOW));
    }

    @Test
    @DisplayName("RF-012: solo se califica un servicio terminado, de 1 a 5 y una sola vez")
    void rating() {
        ServiceExecution execution = ServiceExecution.assign(10L, 3);
        assertThrows(ConflictException.class, () -> execution.rate(5, null, NOW));

        execution.start(NOW);
        execution.finish(NOW);
        assertThrows(InvalidValueException.class, () -> execution.rate(6, null, NOW));

        execution.rate(4, "  muy bien  ", NOW);
        assertEquals((short) 4, execution.rating());
        assertEquals("muy bien", execution.comment());
        assertThrows(ConflictException.class, () -> execution.rate(5, null, NOW));
    }

    @Test
    @DisplayName("un comentario vacio queda sin comentario")
    void blankComment() {
        ServiceExecution execution = ServiceExecution.assign(10L, 3);
        execution.start(NOW);
        execution.finish(NOW);
        execution.rate(5, "   ", NOW);
        assertNull(execution.comment());
    }
}
