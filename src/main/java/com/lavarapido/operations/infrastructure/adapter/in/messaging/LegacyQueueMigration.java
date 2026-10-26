package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OPS-04: la cola de antes (operations-service.events) no tenía DLX y RabbitMQ no deja cambiarle los
 * argumentos. Al arrancar, los mensajes que le queden (reservas confirmadas mientras el servicio estaba
 * apagado) se pasan a la cola nueva y la vieja se borra. Si RabbitMQ no responde, se intenta en el
 * siguiente arranque: la cola vieja no estorba, solo se queda con copias.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
class LegacyQueueMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyQueueMigration.class);
    // tope por arranque, por si algo la sigue llenando
    private static final int MAX_MOVED = 10_000;

    private final AmqpAdmin admin;
    private final AmqpTemplate rabbit;

    LegacyQueueMigration(AmqpAdmin admin, AmqpTemplate rabbit) {
        this.admin = admin;
        this.rabbit = rabbit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        try {
            migrate();
        } catch (AmqpException e) {
            log.warn("Could not migrate the old queue {}: {}", RabbitConsumerConfig.LEGACY_QUEUE, e.getMessage());
        }
    }

    /** @return cuántos mensajes se pasaron a la cola nueva (-1 si no había nada que migrar todavía) */
    int migrate() {
        QueueInformation legacy = admin.getQueueInfo(RabbitConsumerConfig.LEGACY_QUEUE);
        // sin la cola nueva declarada, lo que se mueva se perdería: se deja para el siguiente arranque
        if (legacy == null || admin.getQueueInfo(RabbitConsumerConfig.QUEUE) == null) {
            return -1;
        }
        // primero deja de recibir; lo que ya tenía se mueve (la asignación automática es idempotente,
        // así que un mensaje que llegó a las dos colas no asigna dos veces)
        admin.removeBinding(new Binding(RabbitConsumerConfig.LEGACY_QUEUE, Binding.DestinationType.QUEUE,
                RabbitConsumerConfig.EXCHANGE, RabbitConsumerConfig.ROUTING_KEY, null));
        int moved = 0;
        Message message;
        while (moved < MAX_MOVED && (message = rabbit.receive(RabbitConsumerConfig.LEGACY_QUEUE)) != null) {
            // por el exchange por defecto: llega directo a la cola nueva, sin pasar otra vez por carwash.events
            rabbit.send("", RabbitConsumerConfig.QUEUE, message);
            moved++;
        }
        if (moved == MAX_MOVED) {
            log.warn("Moved {} message(s) from the old queue {}; the rest moves on the next start", moved,
                    RabbitConsumerConfig.LEGACY_QUEUE);
            return moved;
        }
        admin.deleteQueue(RabbitConsumerConfig.LEGACY_QUEUE);
        log.info("Old queue {} removed after moving {} message(s) to {}", RabbitConsumerConfig.LEGACY_QUEUE, moved,
                RabbitConsumerConfig.QUEUE);
        return moved;
    }
}
