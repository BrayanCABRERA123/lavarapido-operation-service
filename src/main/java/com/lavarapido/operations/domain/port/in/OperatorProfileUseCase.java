package com.lavarapido.operations.domain.port.in;

import com.lavarapido.operations.domain.model.OperatorProfile;

import java.util.List;

/** El admin ve y edita la especialidad y las habilidades de los operarios. */
public interface OperatorProfileUseCase {

    record OperatorProfileView(int operatorId, OperatorProfile profile) {
    }

    /** Perfil de todos los operarios (los que no tienen uno salen vacíos). */
    List<OperatorProfileView> profiles();

    OperatorProfileView updateProfile(int operatorId, String specialty, List<OperatorProfile.Skill> skills,
                                      long adminId);
}
