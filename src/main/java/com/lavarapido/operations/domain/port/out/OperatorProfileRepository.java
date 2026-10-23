package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.OperatorProfile;

import java.util.Map;

/** Especialidad y habilidades de cada operario (execution.operator_profile y operator_skill, 037). */
public interface OperatorProfileRepository {

    /** Por operario; los que no tienen perfil no aparecen. */
    Map<Integer, OperatorProfile> findAll();

    OperatorProfile find(int operatorId);

    /** Reemplaza la especialidad y la lista completa de habilidades. */
    void replace(int operatorId, OperatorProfile profile, long actor);
}
