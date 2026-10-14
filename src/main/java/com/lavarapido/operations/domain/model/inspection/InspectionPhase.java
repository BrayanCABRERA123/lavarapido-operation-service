package com.lavarapido.operations.domain.model.inspection;

/** Fases del informe de inspeccion, en el orden en que se recorre el vehiculo. */
public enum InspectionPhase {
    RECEPTION,
    EXTERIOR,
    INTERIOR,
    ENGINE,
    DELIVERY
}
