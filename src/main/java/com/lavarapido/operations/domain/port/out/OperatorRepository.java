package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.model.Absence;
import com.lavarapido.operations.domain.model.Operator;
import com.lavarapido.operations.domain.model.WeeklyAvailability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Operarios, su turno semanal y sus ausencias (esquema execution). */
public interface OperatorRepository {

    List<Operator> findAll();

    Optional<Operator> findById(int operatorId);

    Optional<Operator> findByUserId(long userId);

    Operator save(Operator operator, long actor);

    List<WeeklyAvailability> availability(int operatorId);

    /** Reemplaza el turno semanal completo del operario. */
    void replaceAvailability(int operatorId, List<WeeklyAvailability> week, long actor);

    /** Ausencias que tocan el rango [from, to). */
    List<Absence> absences(int operatorId, Instant from, Instant to);

    Absence addAbsence(int operatorId, Absence absence, long actor);

    /** Borrado logico; false si no existe o no es de ese operario. */
    boolean removeAbsence(int operatorId, int absenceId, long actor);
}
