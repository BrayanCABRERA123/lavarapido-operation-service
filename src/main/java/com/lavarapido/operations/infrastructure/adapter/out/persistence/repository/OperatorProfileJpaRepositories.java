package com.lavarapido.operations.infrastructure.adapter.out.persistence.repository;

import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorProfileJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorSkillJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public final class OperatorProfileJpaRepositories {

    private OperatorProfileJpaRepositories() {
    }

    public interface OperatorProfileJpaRepository extends JpaRepository<OperatorProfileJpaEntity, Integer> {
    }

    public interface OperatorSkillJpaRepository extends JpaRepository<OperatorSkillJpaEntity, Integer> {

        List<OperatorSkillJpaEntity> findByOperatorIdOrderByDisplayOrder(int operatorId);

        List<OperatorSkillJpaEntity> findAllByOrderByOperatorIdAscDisplayOrderAsc();

        // se borra antes de insertar la lista nueva: uq_operator_skill_name no admite repetidos
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("delete from OperatorSkillJpaEntity s where s.operatorId = :operatorId")
        void deleteByOperatorId(int operatorId);
    }
}
