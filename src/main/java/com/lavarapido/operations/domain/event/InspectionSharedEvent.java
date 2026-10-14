package com.lavarapido.operations.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * El admin compartio el informe de inspeccion con el cliente (execution.inspection_shared).
 * notification-service arma el aviso con el resumen y el enlace publico; el texto no viaja aqui.
 */
public record InspectionSharedEvent(long bookingId, String bookingCode, Long customerUserId, String plate,
                                    String publicToken, int phasesDone, int phasesTotal, int findings,
                                    int majorFindings, List<String> areas, Instant occurredAt) {

    public static final String EVENT_NAME = "InspectionShared";
    public static final String ROUTING_KEY = "execution.inspection_shared";
}
