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

    @Test
    @DisplayName("el admin la cierra como atendida una sola vez, sin horas inventadas; iniciarla borra el aviso")
    void closingAsAttendedAndTheNotice() {
        ServiceExecution execution = ServiceExecution.assign(500L, 3);
        execution.followUpNoticeSent(NOW);
        execution.start(NOW.plusSeconds(60));
        assertNull(execution.followUpNoticeAt());

        ServiceExecution forgotten = ServiceExecution.assign(501L, 3);
        forgotten.closeAsAttended();
        assertEquals(ExecutionStatus.COMPLETED, forgotten.status());
        assertNull(forgotten.startedAt());
        assertNull(forgotten.finishedAt());
        assertThrows(ConflictException.class, forgotten::closeAsAttended);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("B-01: las notas de la asignacion se guardan recortadas y se reemplazan al reasignar")
    void assignmentNotesAreKept() {
        ServiceExecution execution = ServiceExecution.assign(500L, 3, "  Llevar cera extra  ");
        org.junit.jupiter.api.Assertions.assertEquals("Llevar cera extra", execution.assignmentNotes());

        execution.reassign(4, "   ");
        org.junit.jupiter.api.Assertions.assertEquals(4, execution.operatorId());
        org.junit.jupiter.api.Assertions.assertNull(execution.assignmentNotes());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.lavarapido.operations.domain.exception.InvalidValueException.class,
                () -> ServiceExecution.assign(500L, 3, "x".repeat(301)));
    }
}
