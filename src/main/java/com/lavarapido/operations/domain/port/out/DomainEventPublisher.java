package com.lavarapido.operations.domain.port.out;

import com.lavarapido.operations.domain.event.ExecutionEvent;

/** Publica eventos de ejecucion despues de que la transaccion se confirma (ADR-004). */
public interface DomainEventPublisher {

    void publish(ExecutionEvent event);
}
