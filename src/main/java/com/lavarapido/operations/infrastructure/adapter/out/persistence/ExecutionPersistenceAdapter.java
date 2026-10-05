package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.ExecutionStatus;
import com.lavarapido.operations.domain.model.ServiceExecution;
import com.lavarapido.operations.domain.port.out.ExecutionRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.ServiceExecutionJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.ExecutionJpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
class ExecutionPersistenceAdapter implements ExecutionRepository {

    private final ExecutionJpaRepository executions;

    ExecutionPersistenceAdapter(ExecutionJpaRepository executions) {
        this.executions = executions;
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
                e.getComment(), e.getRatedAt(), e.getCommentVisible());
    }
}
