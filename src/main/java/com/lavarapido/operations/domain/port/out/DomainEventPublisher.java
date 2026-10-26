package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.event.ExecutionEvent;
import com.lavarapido.operations.domain.event.InspectionSharedEvent;

/** Publica eventos de ejecucion despues de que la transaccion se confirma (ADR-004). */
public interface DomainEventPublisher {

    void publish(ExecutionEvent event);

    /**
     * El admin compartio el informe de inspeccion con el cliente. OPS-08: se publica enseguida y con la
     * confirmacion del broker; si no se pudo, DependencyUnavailableException NOTIFICATION_NOT_SENT (503).
     */
    void publishInspectionShared(InspectionSharedEvent event);
}
