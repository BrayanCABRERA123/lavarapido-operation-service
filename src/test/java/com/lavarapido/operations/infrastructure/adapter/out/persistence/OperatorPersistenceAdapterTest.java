package com.lavarapido.operations.infrastructure.adapter.out.persistence;

import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.entity.OperatorJpaEntity;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.AbsenceJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.AvailabilityJpaRepository;
import com.lavarapido.operations.infrastructure.adapter.out.persistence.repository.OperationsJpaRepositories.OperatorJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("OperatorPersistenceAdapter (alta del operario)")
class OperatorPersistenceAdapterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final OperatorJpaRepository operators = mock(OperatorJpaRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private OperatorPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        adapter = new OperatorPersistenceAdapter(operators, mock(AvailabilityJpaRepository.class),
                mock(AbsenceJpaRepository.class), Clock.systemUTC(), transactions);
    }

    @Test
    @DisplayName("crea la fila del operario en su propia transacción")
    void createsTheRow() {
        OperatorJpaEntity saved = entity(9, 70L);
        given(operators.save(any(OperatorJpaEntity.class))).willReturn(saved);

        Operator operator = adapter.provision(70L, TODAY, 70L);

        assertEquals(9, operator.id());
        verify(transactions).commit(any());
    }

    @Test
    @DisplayName("si otra petición la creó al mismo tiempo, devuelve esa en vez de fallar con uq_operator_user")
    void usesTheRowCreatedByAConcurrentRequest() {
        given(operators.save(any(OperatorJpaEntity.class)))
                .willThrow(new DataIntegrityViolationException("Violation of UNIQUE KEY constraint 'uq_operator_user'"));
        OperatorJpaEntity existing = entity(9, 70L);
        given(operators.findByUserIdAndDeletedAtIsNull(70L)).willReturn(Optional.of(existing));

        Operator operator = adapter.provision(70L, TODAY, 70L);

        assertEquals(9, operator.id());
        assertEquals(70L, operator.userId());
        verify(transactions).rollback(any());
    }

    private static OperatorJpaEntity entity(int id, long userId) {
        OperatorJpaEntity entity = mock(OperatorJpaEntity.class);
        given(entity.getId()).willReturn(id);
        given(entity.getUserId()).willReturn(userId);
        given(entity.getHiredOn()).willReturn(TODAY);
        given(entity.getActive()).willReturn(true);
        return entity;
    }
}
