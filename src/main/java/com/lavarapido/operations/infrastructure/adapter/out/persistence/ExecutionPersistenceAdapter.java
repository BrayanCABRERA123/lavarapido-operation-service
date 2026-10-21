package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.ServiceExecutionJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.ExecutionJpaRepository;
import com.lavarapido.operations.domain.exception.ConflictException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
class ExecutionPersistenceAdapter implements ExecutionRepository {

    /**
     * sp_getapplock con dueño Transaction (igual que la agenda de booking-service): el candado se
     * suelta solo al terminar la transaccion. Devuelve >= 0 si lo obtuvo; negativo si se vencio la
     * espera (10 s).
     */
    private static final String LOCK_SQL = """
            SET NOCOUNT ON;
            -- el driver de SQL Server abre la transaccion de Spring en la base con la primera lectura
            -- de una tabla; sin ella sp_getapplock responde -999. Esta lectura (sin filas de salida)
            -- la abre; un BEGIN TRANSACTION abriria dos niveles y el commit dejaria uno colgado.
            DECLARE @open SMALLINT;
            SELECT TOP (1) @open = execution_status_id FROM execution.execution_status;
            DECLARE @result INT;
            EXEC @result = sp_getapplock @Resource = 'operations-assignment', @LockMode = 'Exclusive',
                                         @LockOwner = 'Transaction', @LockTimeout = 10000;
            SELECT @result;
            """;

    private final ExecutionJpaRepository executions;
    private final EntityManager entityManager;

    ExecutionPersistenceAdapter(ExecutionJpaRepository executions, EntityManager entityManager) {
        this.executions = executions;
        this.entityManager = entityManager;
    }

    @Override
    public void lockAssignments() {
        Object result = entityManager.createNativeQuery(LOCK_SQL).getSingleResult();
        if (result instanceof Number number && number.intValue() < 0) {
            throw new ConflictException("ASSIGNMENT_BUSY", "Another assignment is in progress, try again");
        }
    }

    @Override
    public List<ServiceExecution> findByLineIds(Collection<Long> bookingServiceIds) {
        if (bookingServiceIds.isEmpty()) return List.of();
        return executions.findByBookingServiceIdInAndDeletedAtIsNull(bookingServiceIds).stream()
                .map(ExecutionPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<ServiceExecution> findByOperator(int operatorId) {
        return executions.findByOperatorIdAndDeletedAtIsNull(operatorId).stream()
                .map(ExecutionPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<ServiceExecution> findRated(int operatorId) {
        return executions.findByOperatorIdAndRatingIsNotNullAndDeletedAtIsNull(operatorId).stream()
                .map(ExecutionPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<ServiceExecution> saveAll(List<ServiceExecution> list, long actor) {
        List<ServiceExecution> saved = new ArrayList<>();
        for (ServiceExecution execution : list) {
            ServiceExecutionJpaEntity entity = execution.id() == null ? new ServiceExecutionJpaEntity()
                    : executions.findById(execution.id()).orElseThrow();
            if (execution.id() == null) {
                entity.setBookingServiceId(execution.bookingServiceId());
                entity.setCreatedBy(actor);
            } else {
                entity.setUpdatedBy(actor);
            }
            entity.setOperatorId(execution.operatorId());
            entity.setStatusId(execution.status().id());
            entity.setStartedAt(execution.startedAt());
            entity.setFinishedAt(execution.finishedAt());
            entity.setRating(execution.rating());
            entity.setComment(execution.comment());
            entity.setRatedAt(execution.ratedAt());
            entity.setCommentVisible(execution.commentVisible());
            entity.setFollowUpNoticeAt(execution.followUpNoticeAt());
            ServiceExecutionJpaEntity row = executions.save(entity);
            if (execution.id() == null) {
                execution.assignId(row.getId());
            }
            saved.add(execution);
        }
        return saved;
    }

    private static ServiceExecution toDomain(ServiceExecutionJpaEntity e) {
        return ServiceExecution.restore(e.getId(), e.getBookingServiceId(), e.getOperatorId(),
                ExecutionStatus.ofId(e.getStatusId()), e.getStartedAt(), e.getFinishedAt(), e.getRating(),
                e.getComment(), e.getRatedAt(), e.getCommentVisible(), e.getFollowUpNoticeAt());
    }
}
