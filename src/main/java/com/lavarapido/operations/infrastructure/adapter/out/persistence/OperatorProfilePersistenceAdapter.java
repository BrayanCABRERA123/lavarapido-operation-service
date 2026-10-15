package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.OperatorProfile;
import com.lavarapido.operations.domain.port.out.OperatorProfileRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorProfileJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorSkillJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperatorProfileJpaRepositories.OperatorProfileJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperatorProfileJpaRepositories.OperatorSkillJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
class OperatorProfilePersistenceAdapter implements OperatorProfileRepository {

    private final OperatorProfileJpaRepository profiles;
    private final OperatorSkillJpaRepository skills;
    private final Clock clock;

    OperatorProfilePersistenceAdapter(OperatorProfileJpaRepository profiles, OperatorSkillJpaRepository skills,
                                      Clock clock) {
        this.profiles = profiles;
        this.skills = skills;
        this.clock = clock;
    }

    @Override
    public Map<Integer, OperatorProfile> findAll() {
        Map<Integer, String> specialties = new HashMap<>();
        profiles.findAll().forEach(p -> specialties.put(p.getOperatorId(), p.getSpecialty()));
        Map<Integer, List<OperatorProfile.Skill>> skillsByOperator = new HashMap<>();
        for (OperatorSkillJpaEntity row : skills.findAllByOrderByOperatorIdAscDisplayOrderAsc()) {
            skillsByOperator.computeIfAbsent(row.getOperatorId(), id -> new ArrayList<>()).add(toDomain(row));
        }
        Map<Integer, OperatorProfile> result = new HashMap<>();
        specialties.forEach((id, specialty) ->
                result.put(id, new OperatorProfile(specialty, skillsByOperator.getOrDefault(id, List.of()))));
        skillsByOperator.forEach((id, list) -> result.putIfAbsent(id, new OperatorProfile(null, list)));
        return result;
    }

    @Override
    public OperatorProfile find(int operatorId) {
        String specialty = profiles.findById(operatorId).map(OperatorProfileJpaEntity::getSpecialty).orElse(null);
        List<OperatorProfile.Skill> list = skills.findByOperatorIdOrderByDisplayOrder(operatorId).stream()
                .map(OperatorProfilePersistenceAdapter::toDomain).toList();
        return new OperatorProfile(specialty, list);
    }

    @Override
    public void replace(int operatorId, OperatorProfile profile, long actor) {
        OperatorProfileJpaEntity row = profiles.findById(operatorId)
                .orElseGet(() -> new OperatorProfileJpaEntity(operatorId));
        row.setSpecialty(profile.specialty());
        row.setUpdatedAt(clock.instant());
        row.setUpdatedBy(actor);
        profiles.save(row);

        skills.deleteByOperatorId(operatorId);
        short order = 0;
        for (OperatorProfile.Skill skill : profile.skills()) {
            skills.save(new OperatorSkillJpaEntity(operatorId, skill.name(), skill.level().name(), order++));
        }
    }

    private static OperatorProfile.Skill toDomain(OperatorSkillJpaEntity row) {
        return new OperatorProfile.Skill(row.getName(), OperatorProfile.Level.valueOf(row.getLevel()));
    }
}
