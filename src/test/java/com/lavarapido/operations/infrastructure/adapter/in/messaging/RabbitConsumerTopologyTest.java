package com.lavarapido.operations.infrastructure.adapter.in.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("Topología de RabbitMQ de operations (cola, DLQ y migración de la cola vieja)")
class RabbitConsumerTopologyTest {

    @Test
    @DisplayName("OPS-04: la cola de booking.confirmed manda a la DLQ lo que sigue fallando")
    void theQueueHasADeadLetterQueue() {
        Declarables topology = new RabbitConsumerConfig().operationsTopology(new TopicExchange("carwash.events"));

        Queue queue = topology.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(RabbitConsumerConfig.QUEUE)).findFirst().orElseThrow();
        assertEquals("carwash.events.dlx", queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("operations-service.booking-confirmed.dlq", queue.getArguments().get("x-dead-letter-routing-key"));
        assertTrue(queue.isDurable());

        List<Binding> bindings = topology.getDeclarablesByType(Binding.class);
        assertTrue(bindings.stream().anyMatch(b -> b.getDestination().equals(RabbitConsumerConfig.QUEUE)
                && b.getExchange().equals("carwash.events") && b.getRoutingKey().equals("booking.confirmed")));
        assertTrue(bindings.stream().anyMatch(b -> b.getDestination().equals(RabbitConsumerConfig.DEAD_LETTER_QUEUE)
                && b.getExchange().equals("carwash.events.dlx")));
    }

    @Test
    @DisplayName("OPS-04: la cola vieja (sin DLX) se desenlaza, sus mensajes pasan a la nueva y se borra")
    void theOldQueueIsMovedAndRemoved() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        AmqpTemplate rabbit = mock(AmqpTemplate.class);
        given(admin.getQueueInfo(RabbitConsumerConfig.LEGACY_QUEUE)).willReturn(new QueueInformation("old", 2, 0));
        given(admin.getQueueInfo(RabbitConsumerConfig.QUEUE)).willReturn(new QueueInformation("new", 0, 1));
        Message pending = new Message(new byte[0], new MessageProperties());
        given(rabbit.receive(RabbitConsumerConfig.LEGACY_QUEUE)).willReturn(pending, pending, null);

        assertEquals(2, new LegacyQueueMigration(admin, rabbit).migrate());

        verify(admin).removeBinding(any(Binding.class));
        verify(rabbit, times(2)).send("", RabbitConsumerConfig.QUEUE, pending);
        verify(admin).deleteQueue(RabbitConsumerConfig.LEGACY_QUEUE);
    }

    @Test
    @DisplayName("sin cola vieja (o sin la nueva todavía) no se toca nada")
    void nothingToMigrate() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        AmqpTemplate rabbit = mock(AmqpTemplate.class);

        assertEquals(-1, new LegacyQueueMigration(admin, rabbit).migrate());

        verify(admin, never()).deleteQueue(anyString());
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class));
        verify(rabbit, never()).receive(eq(RabbitConsumerConfig.LEGACY_QUEUE));
    }
}
