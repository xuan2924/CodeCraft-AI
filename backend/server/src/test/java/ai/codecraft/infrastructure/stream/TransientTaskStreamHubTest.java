package ai.codecraft.infrastructure.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransientTaskStreamHubTest {
    @Test
    void commitsWakeButRollbackDoesNotAndSubscriptionsAreReleased() throws Exception {
        var hub = new TransientTaskStreamHub();
        var calls = new AtomicInteger();
        var subscription = hub.subscribe(task -> calls.incrementAndGet());
        TransactionSynchronizationManager.initSynchronization();
        try {
            hub.afterCommit("task");
            assertEquals(0, calls.get());
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCommit();
            assertEquals(1, calls.get());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        TransactionSynchronizationManager.initSynchronization();
        try {
            hub.afterCommit("task");
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertEquals(1, calls.get());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        subscription.close();
        hub.signal("task");
        assertEquals(1, calls.get());
    }

    @Test
    void previewsReplaceWithinHardBoundsAndCannotBeMutatedByConsumers() {
        var hub = new TransientTaskStreamHub();
        byte[] bytes = {1};
        hub.preview("task", "turn", bytes);
        bytes[0] = 2;
        var first = hub.latest("task");
        first.frame()[0] = 3;
        assertEquals(1, hub.latest("task").frame()[0]);
        hub.preview("task", "turn", new byte[]{4});
        assertEquals(first.sequence() + 1, hub.latest("task").sequence());
        assertThrows(IllegalArgumentException.class, () -> hub.preview("task", "turn", new byte[32769]));
        for (int index = 0; index < 127; index++) hub.preview("task" + index, "turn", bytes);
        hub.preview("task", "updated", bytes);
        hub.preview("new", "turn", bytes);
        assertEquals("turn", hub.latest("new").turnId());
        assertEquals("updated", hub.latest("task").turnId());
        assertNull(hub.latest("task0"));
        hub.clear("task");
        assertNull(hub.latest("task"));
    }
}
