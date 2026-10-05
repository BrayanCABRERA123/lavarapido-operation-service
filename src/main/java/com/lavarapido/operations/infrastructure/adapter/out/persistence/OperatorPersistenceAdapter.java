package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.WeeklyAvailability;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAbsenceJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorAvailabilityJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.AbsenceJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.AvailabilityJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.OperatorJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class OperatorPersistenceAdapter implements OperatorRepository {

    private final OperatorJpaRepository operators;
    private final AvailabilityJpaRepository availability;
    private final AbsenceJpaRepository absences;
    private final Clock clock;

    OperatorPersistenceAdapter(OperatorJpaRepository operators, AvailabilityJpaRepository availability,
                               AbsenceJpaRepository absences, Clock clock) {
        this.operators = operators;
        this.availability = availability;
        this.absences = absences;
        this.clock = clock;
    }

    @Override
    public List<Operator> findAll() {
        return operators.findByDeletedAtIsNullOrderByIdAsc().stream().map(OperatorPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Optional<Operator> findById(int operatorId) {
        return operators.findByIdAndDeletedAtIsNull(operatorId).map(OperatorPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Operator> findByUserId(long userId) {
        return operators.findByUserIdAndDeletedAtIsNull(userId).map(OperatorPersistenceAdapter::toDomain);
    }

    @Override
    public Operator save(Operator operator, long actor) {
        OperatorJpaEntity entity = operator.id() == null ? new OperatorJpaEntity()
                : operators.findById(operator.id()).orElseThrow();
        if (operator.id() == null) {
            entity.setUserId(operator.userId());
            entity.setHiredOn(operator.hiredOn());
            entity.setCreatedBy(actor == 0 ? null : actor);
        } else {
            entity.setUpdatedBy(actor);
        }
        entity.setActive(operator.active());
        OperatorJpaEntity saved = operators.save(entity);
        if (operator.id() == null) {
            operator.assignId(saved.getId());
        }
        return operator;
    }

    @Override
    public List<WeeklyAvailability> availability(int operatorId) {
        return availability.findByOperatorId(operatorId).stream()
                .filter(OperatorAvailabilityJpaEntity::getActive)
                .map(e -> new WeeklyAvailability(e.getDayOfWeek(), e.getStartsAt(), e.getEndsAt()))
                .sorted(Comparator.comparing(WeeklyAvailability::dayOfWeek))
                .toList();
    }

    @Override
    public void replaceAvailability(int operatorId, List<WeeklyAvailability> week, long actor) {
        Map<Short, OperatorAvailabilityJpaEntity> current = availability.findByOperatorId(operatorId).stream()
                .collect(Collectors.toMap(OperatorAvailabilityJpaEntity::getDayOfWeek, Function.identity()));
        Map<Short, WeeklyAvailability> wanted = week.stream()
                .collect(Collectors.toMap(WeeklyAvailability::dayOfWeek, Function.identity()));
        // un dia por fila (uq_operator_availability): se actualiza, se prende o se apaga
        for (short day = 1; day <= 7; day++) {
            OperatorAvailabilityJpaEntity row = current.get(day);
            WeeklyAvailability shift = wanted.get(day);
            if (shift == null) {
                if (row != null && row.getActive()) {
                    row.setActive(false);
                    row.setUpdatedBy(actor);
                    availability.save(row);
                }
                continue;
            }
            if (row == null) {
                row = new OperatorAvailabilityJpaEntity();
                row.setOperatorId(operatorId);
                row.setDayOfWeek(day);
                row.setCreatedBy(actor);
            } else {
                row.setUpdatedBy(actor);
            }
            row.setStartsAt(shift.startsAt());
            row.setEndsAt(shift.endsAt());
            row.setActive(true);
            availability.save(row);
        }
    }

    @Override
    public List<Absence> absences(int operatorId, Instant from, Instant to) {
        return absences.findByOperatorIdAndDeletedAtIsNullAndStartsAtBeforeAndEndsAtAfterOrderByStartsAtAsc(operatorId, to, from)
                .stream().map(OperatorPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Absence addAbsence(int operatorId, Absence absence, long actor) {
        OperatorAbsenceJpaEntity entity = new OperatorAbsenceJpaEntity();
        entity.setOperatorId(operatorId);
        entity.setStartsAt(absence.startsAt());
        entity.setEndsAt(absence.endsAt());
        entity.setReason(absence.reason());
        entity.setCreatedBy(actor);
        return toDomain(absences.saveAndFlush(entity));
    }

    @Override
    public boolean removeAbsence(int operatorId, int absenceId, long actor) {
        return absences.findByIdAndOperatorIdAndDeletedAtIsNull(absenceId, operatorId)
                .map(entity -> {
                    entity.setDeletedAt(clock.instant());
                    entity.setDeletedBy(actor);
                    absences.save(entity);
                    return true;
                })
                .orElse(false);
    }

    private static Operator toDomain(OperatorJpaEntity entity) {
        return Operator.restore(entity.getId(), entity.getUserId(), entity.getHiredOn(), entity.getActive());
    }

    private static Absence toDomain(OperatorAbsenceJpaEntity entity) {
        return new Absence(entity.getId(), entity.getStartsAt(), entity.getEndsAt(), entity.getReason());
    }
}
