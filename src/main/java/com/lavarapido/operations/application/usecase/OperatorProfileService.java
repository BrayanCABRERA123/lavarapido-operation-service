package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.OperatorProfile;
import com.lavarapido.operations.domain.port.in.OperatorProfileUseCase;
import com.lavarapido.operations.domain.port.out.OperatorProfileRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Ficha del operario: especialidad y habilidades (antes la pantalla las mostraba vacías). */
@Service
@Transactional
public class OperatorProfileService implements OperatorProfileUseCase {

    private final OperatorRepository operators;
    private final OperatorProfileRepository profiles;

    public OperatorProfileService(OperatorRepository operators, OperatorProfileRepository profiles) {
        this.operators = operators;
        this.profiles = profiles;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperatorProfileView> profiles() {
        Map<Integer, OperatorProfile> saved = profiles.findAll();
        return operators.findAll().stream()
                .map(Operator::id)
                .map(id -> new OperatorProfileView(id, saved.getOrDefault(id, OperatorProfile.EMPTY)))
                .toList();
    }

    @Override
    public OperatorProfileView updateProfile(int operatorId, String specialty, List<OperatorProfile.Skill> skills,
                                             long adminId) {
        operators.findById(operatorId)
                .orElseThrow(() -> new NotFoundException("OPERATOR_NOT_FOUND", "Operator not found"));
        OperatorProfile profile = OperatorProfile.of(specialty, skills);
        profiles.replace(operatorId, profile, adminId);
        return new OperatorProfileView(operatorId, profile);
    }
}
