package com.lavarapido.operations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Una habilidad del operario: nombre y nivel (execution.operator_skill, migración 037). */
@Entity
@Table(schema = "execution", name = "operator_skill")
public class OperatorSkillJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operator_skill_id")
    private Integer id;

    @Column(name = "operator_id")
    private Integer operatorId;

    @Column(name = "name")
    private String name;

    @Column(name = "skill_level")
    private String level;

    @Column(name = "display_order")
    private Short displayOrder;

    protected OperatorSkillJpaEntity() {
    }

    public OperatorSkillJpaEntity(Integer operatorId, String name, String level, Short displayOrder) {
        this.operatorId = operatorId;
        this.name = name;
        this.level = level;
        this.displayOrder = displayOrder;
    }

    public Integer getOperatorId() {
        return operatorId;
    }

    public String getName() {
        return name;
    }

    public String getLevel() {
        return level;
    }

    public Short getDisplayOrder() {
        return displayOrder;
    }
}
