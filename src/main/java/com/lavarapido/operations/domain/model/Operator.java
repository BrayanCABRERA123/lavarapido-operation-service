package com.lavarapido.operations.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Operario (execution.operator). Cuelga de la cuenta de security (user_id), no de la persona:
 * el nombre y el contacto se leen de security-service (models.md, operator).
 */
public final class Operator {

    private Integer id;
    private final long userId;
    private final LocalDate hiredOn;
    private boolean active;

    private Operator(Integer id, long userId, LocalDate hiredOn, boolean active) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        this.id = id;
        this.userId = userId;
        this.hiredOn = Objects.requireNonNull(hiredOn, "hiredOn");
        this.active = active;
    }

    /** Cuenta con rol OPERATOR que aun no tiene fila de operario: se crea activa desde hoy. */
    public static Operator provision(long userId, LocalDate today) {
        return new Operator(null, userId, today, true);
    }

    public static Operator restore(int id, long userId, LocalDate hiredOn, boolean active) {
        return new Operator(id, userId, hiredOn, active);
    }

    public void assignId(int newId) {
        if (id != null) {
            throw new IllegalStateException("The operator already has an id");
        }
        id = newId;
    }

    public void setActive(boolean value) {
        active = value;
    }

    public Integer id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public LocalDate hiredOn() {
        return hiredOn;
    }

    public boolean active() {
        return active;
    }
}
