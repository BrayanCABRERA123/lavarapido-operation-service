package com.lavarapido.operations.domain.service;

import com.lavarapido.operations.domain.service.AutoAssignmentPolicy.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Asignación automática (RF-008/010)")
class AutoAssignmentPolicyTest {

    private static final List<Candidate> FREE = List.of(new Candidate(2, 3), new Candidate(3, 1), new Candidate(5, 1));

    @Test
    @DisplayName("el operario que eligió el cliente, si está libre")
    void preferredWhenFree() {
        assertEquals(Optional.of(2), AutoAssignmentPolicy.choose(FREE, 2));
    }

    @Test
    @DisplayName("sin preferencia o si el elegido no puede: el de menos reservas ese día, y entre iguales el de id menor")
    void leastBusyOtherwise() {
        assertEquals(Optional.of(3), AutoAssignmentPolicy.choose(FREE, null));
        assertEquals(Optional.of(3), AutoAssignmentPolicy.choose(FREE, 9));
    }

    @Test
    @DisplayName("nadie libre: no se asigna (el administrador recibe la alerta de reserva sin operario)")
    void nobodyFree() {
        assertEquals(Optional.empty(), AutoAssignmentPolicy.choose(List.of(), 2));
    }
}
