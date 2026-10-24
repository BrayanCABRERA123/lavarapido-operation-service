package com.lavarapido.operations.infrastructure.adapter.out.persistence.repository;

import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAbsenceJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAvailabilityJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.ServiceExecutionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

        /** OPS-03: la fila del operario con UPDLOCK hasta que termine la transacción. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select o from OperatorJpaEntity o where o.id = :id")
        Optional<OperatorJpaEntity> lockById(@Param("id") int id);
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

        /** OPS-03: las mismas filas, con UPDLOCK hasta que termine la transacción. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select e from ServiceExecutionJpaEntity e where e.bookingServiceId in :ids and e.deletedAt is null")
        List<ServiceExecutionJpaEntity> lockByBookingServiceIds(@Param("ids") Collection<Long> bookingServiceIds);

        List<ServiceExecutionJpaEntity> findByOperatorIdAndDeletedAtIsNull(int operatorId);

        List<ServiceExecutionJpaEntity> findByOperatorIdAndRatingIsNotNullAndDeletedAtIsNull(int operatorId);
    }
}
