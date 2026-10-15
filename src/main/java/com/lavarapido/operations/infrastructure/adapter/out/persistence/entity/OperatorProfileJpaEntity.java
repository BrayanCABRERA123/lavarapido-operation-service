package com.lavarapido.operations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Especialidad del operario (execution.operator_profile, migración 037). */
@Entity
@Table(schema = "execution", name = "operator_profile")
public class OperatorProfileJpaEntity {

    @Id
    @Column(name = "operator_id")
    private Integer operatorId;

    @Column(name = "specialty")
    private String specialty;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected OperatorProfileJpaEntity() {
    }

    public OperatorProfileJpaEntity(Integer operatorId) {
        this.operatorId = operatorId;
    }

    public Integer getOperatorId() {
        return operatorId;
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
