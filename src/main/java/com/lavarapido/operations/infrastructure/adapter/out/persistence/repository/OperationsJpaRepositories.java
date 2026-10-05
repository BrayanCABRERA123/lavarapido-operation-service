package com.lavarapido.operations.infrastructure.adapter.out.persistence.repository;

import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAbsenceJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAvailabilityJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.ServiceExecutionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Repositorios Spring Data del esquema execution (anidados: ver JpaConfig). */
public final class OperationsJpaRepositories {

    private OperationsJpaRepositories() {
    }

    public interface OperatorJpaRepository extends JpaRepository<OperatorJpaEntity, Integer> {

        List<OperatorJpaEntity> findByDeletedAtIsNullOrderByIdAsc();

        Optional<OperatorJpaEntity> findByIdAndDeletedAtIsNull(int id);

        Optional<OperatorJpaEntity> findByUserIdAndDeletedAtIsNull(long userId);
    }

    public interface AvailabilityJpaRepository extends JpaRepository<OperatorAvailabilityJpaEntity, Integer> {

        List<OperatorAvailabilityJpaEntity> findByOperatorId(int operatorId);
    }

    public interface AbsenceJpaRepository extends JpaRepository<OperatorAbsenceJpaEntity, Integer> {

        /** Ausencias que tocan [from, to): empiezan antes de to y terminan despues de from. */
        List<OperatorAbsenceJpaEntity> findByOperatorIdAndDeletedAtIsNullAndStartsAtBeforeAndEndsAtAfterOrderByStartsAtAsc(
                int operatorId, Instant to, Instant from);

        Optional<OperatorAbsenceJpaEntity> findByIdAndOperatorIdAndDeletedAtIsNull(int id, int operatorId);
    }

    public interface ExecutionJpaRepository extends JpaRepository<ServiceExecutionJpaEntity, Long> {

        List<ServiceExecutionJpaEntity> findByBookingServiceIdInAndDeletedAtIsNull(Collection<Long> bookingServiceIds);

        List<ServiceExecutionJpaEntity> findByOperatorIdAndDeletedAtIsNull(int operatorId);

        List<ServiceExecutionJpaEntity> findByOperatorIdAndRatingIsNotNullAndDeletedAtIsNull(int operatorId);
    }
}
