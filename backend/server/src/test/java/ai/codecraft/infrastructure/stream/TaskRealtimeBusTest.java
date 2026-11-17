package ai.codecraft.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.tasks.TaskWakeup;
import ai.codecraft.infrastructure.tasks.TaskWakeupPublisher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaskRealtimeBusTest {
    @Test void twoNodesDeliverPreviewClearAndCommittedWakeupWithoutEcho() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var first = new TaskRealtimeBus(redis);
        var second = new TaskRealtimeBus(redis);
        var hubA = new TransientTaskStreamHub(first);
        var hubB = new TransientTaskStreamHub(second);
        var preview = new CountDownLatch(1);
        var cleared = new CountDownLatch(1);
        var wakeup = new CountDownLatch(1);
        hubB.subscribe(task -> { if (hubB.latest(task) == null) cleared.countDown(); else preview.countDown(); });
        when(redis.convertAndSend(eq(TaskRealtimeBus.CHANNEL), anyString())).thenAnswer(call -> {
            byte[] body = ((String) call.getArgument(1)).getBytes(StandardCharsets.UTF_8);
            first.receive(body); second.receive(body); return 2L;
        });
        try (var publisherA = new ClosingPublisher(new TaskWakeupPublisher(event -> { }, first));
             var publisherB = new ClosingPublisher(new TaskWakeupPublisher(event -> wakeup.countDown(), second))) {
            hubA.preview("task", "turn", "frame".getBytes(StandardCharsets.UTF_8));
            assertThat(preview.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(hubB.latest("task").frame()).isEqualTo(hubA.latest("task").frame());
            assertThat(hubA.latest("task").sequence()).isEqualTo(1);
            hubA.clear("task");
            assertThat(cleared.await(2, TimeUnit.SECONDS)).isTrue();
            TransactionSynchronizationManager.initSynchronization();
            try {
                publisherA.value().afterCommit(TaskWakeup.Lane.values()[0]);
                assertThat(wakeup.getCount()).isEqualTo(1);
                TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            assertThat(wakeup.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { first.close(); second.close(); }
    }

    @Test void invalidRemoteFramesDoNotReachSubscribers() {
        var bus = new TaskRealtimeBus(null);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        bus.subscribe(hint -> calls.incrementAndGet());
        bus.receive(new byte[65537]); bus.receive("bad-json".getBytes(StandardCharsets.UTF_8));
        assertThat(calls).hasValue(0);
        bus.close();
    }
    private record ClosingPublisher(TaskWakeupPublisher value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
