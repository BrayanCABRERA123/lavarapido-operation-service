package com.lavarapido.operations.application.usecase;

import com.lavarapido.operations.domain.exception.InvalidValueException;
import com.lavarapido.operations.domain.exception.NotFoundException;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.OperatorProfile;
import com.lavarapido.operations.domain.model.OperatorProfile.Level;
import com.lavarapido.operations.domain.model.OperatorProfile.Skill;
import com.lavarapido.operations.domain.port.in.OperatorProfileUseCase.OperatorProfileView;
import com.lavarapido.operations.domain.port.out.OperatorProfileRepository;
import com.lavarapido.operations.domain.port.out.OperatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OperatorProfileServiceTest {

    private static final long ADMIN = 1L;

    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final InMemoryProfiles profiles = new InMemoryProfiles();
    private final OperatorProfileService service = new OperatorProfileService(operators, profiles);

    private final Operator oscar = Operator.restore(3, 30L, LocalDate.of(2026, 1, 1), true);
    private final Operator lina = Operator.restore(4, 40L, LocalDate.of(2026, 1, 1), true);

    @BeforeEach
    void setUp() {
        given(operators.findById(3)).willReturn(Optional.of(oscar));
        given(operators.findAll()).willReturn(List.of(oscar, lina));
    }

    @Test
    @DisplayName("guarda la especialidad y las habilidades limpias, y reemplaza la lista al editar")
    void savesAndReplacesTheProfile() {
        service.updateProfile(3, "  Detallado interior ", List.of(new Skill(" Pulido ", Level.EXPERT)), ADMIN);
        OperatorProfileView view = service.updateProfile(3, "Detallado interior",
                List.of(new Skill("Cerámico", Level.CERTIFIED), new Skill("Tapicería", Level.ADVANCED)), ADMIN);

        assertEquals("Detallado interior", view.profile().specialty());
        assertEquals(List.of(new Skill("Cerámico", Level.CERTIFIED), new Skill("Tapicería", Level.ADVANCED)),
                profiles.rows.get(3).skills());
    }

    @Test
    @DisplayName("el listado trae a todos los operarios; los que no tienen perfil salen vacíos")
    void listsEveryOperator() {
        service.updateProfile(3, "Lavado de motor", List.of(), ADMIN);

        List<OperatorProfileView> all = service.profiles();

        assertEquals(2, all.size());
        assertEquals("Lavado de motor", all.get(0).profile().specialty());
        assertNull(all.get(1).profile().specialty());
        assertEquals(List.of(), all.get(1).profile().skills());
    }

    @Test
    @DisplayName("no acepta habilidades repetidas ni operarios que no existen")
    void rejectsInvalidData() {
        assertThrows(InvalidValueException.class, () -> service.updateProfile(3, null,
                List.of(new Skill("Pulido", Level.EXPERT), new Skill("pulido", Level.ADVANCED)), ADMIN));
        assertThrows(NotFoundException.class, () -> service.updateProfile(99, null, List.of(), ADMIN));
    }

    private static final class InMemoryProfiles implements OperatorProfileRepository {

        private final Map<Integer, OperatorProfile> rows = new HashMap<>();

        @Override
        public Map<Integer, OperatorProfile> findAll() {
            return Map.copyOf(rows);
        }

        @Override
        public OperatorProfile find(int operatorId) {
            return rows.getOrDefault(operatorId, OperatorProfile.EMPTY);
        }

        @Override
        public void replace(int operatorId, OperatorProfile profile, long actor) {
            rows.put(operatorId, profile);
        }
    }
}
