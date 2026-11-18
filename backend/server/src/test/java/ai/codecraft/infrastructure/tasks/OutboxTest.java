package ai.codecraft.infrastructure.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxTest {
    private OutboxEvent event(int failures) {
        return new OutboxEvent(UUID.randomUUID().toString(), "TEST", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            0, 0, "interactive", "SENT", UUID.randomUUID().toString(), failures);
    }
    @Test void durableDuplicateIsAckedWithoutRunningBusinessAndDbFailureEscapes() {
        var store = mock(OutboxStore.class); var handler = mock(OutboxHandler.class);
        var consumer = new OutboxConsumer(store, handler); var event = event(0);
        consumer.accept(event.id());
        verify(handler, never()).handle(any());
        when(store.begin(eq(event.id()), anyString())).thenReturn(event);
        doThrow(new IllegalStateException("unknown result")).when(handler).handle(event);
        consumer.accept(event.id());
        verify(store).failed(eq(event), anyString());
        verify(store, never()).complete(any(), anyString());
        doThrow(new IllegalStateException("db unavailable")).when(store).failed(eq(event), anyString());
        assertThatThrownBy(() -> consumer.accept(event.id())).hasMessage("db unavailable");
    }
    @Test void retryIsBoundedAndStaleTokensCannotChangeOutcome() {
        var repository = mock(OutboxRepository.class); var store = new OutboxStore(repository);
        var event = event(3); when(repository.lock(event.id())).thenReturn(event);
        when(repository.release(event.id(), "owner", 16)).thenReturn(1);
        store.failed(event, "owner");
        verify(repository).outcome(event.id(), "PENDING", 1, 16);
        var terminal = new OutboxEvent(event.id(), event.kind(), event.aggregateId(), event.exerciseId(), 0, 0, event.lane(), "SENT", "", 4);
        when(repository.lock(event.id())).thenReturn(terminal);
        store.failed(terminal, "stale");
        verify(repository, never()).dead(anyString(), anyString(), eq(5));
        when(repository.finish(event.id(), "owner", "DEAD")).thenReturn(1);
        store.failed(terminal, "owner");
        verify(repository).dead(event.id(), "HANDLER_FAILED", 5);
        verify(repository).outcome(event.id(), "DEAD", 1, 0);
        assertThat(OutboxStore.retryDelay(1)).isEqualTo(2);
        assertThat(OutboxStore.retryDelay(4)).isEqualTo(16);
    }
    @Test void topologySeparatesLanesAndUsesBoundedManualConsumers() {
        var configuration = new RabbitTaskConfiguration();
        var topology = configuration.taskTopology();
        var queues = topology.getDeclarablesByType(Queue.class);
        assertThat(queues).hasSize(4).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments()).containsEntry("x-overflow", "reject-publish");
        });
        assertThat(topology.getDeclarablesByType(Binding.class)).extracting(Binding::getRoutingKey)
            .containsExactlyInAnyOrder("interactive", "slow", "interactive.dead", "slow.dead");
        var factory = mock(ConnectionFactory.class); var consumer = mock(OutboxConsumer.class);
        var fast = configuration.interactiveTasks(factory, consumer); var slow = configuration.slowTasks(factory, consumer);
        assertThat(fast.getQueueNames()).containsExactly("codecraft.tasks.v1.interactive");
        assertThat(slow.getQueueNames()).containsExactly("codecraft.tasks.v1.slow");
        assertThat(fast.getAcknowledgeMode()).isEqualTo(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(fast, "prefetchCount")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(slow, "prefetchCount")).isEqualTo(1);
    }
}
