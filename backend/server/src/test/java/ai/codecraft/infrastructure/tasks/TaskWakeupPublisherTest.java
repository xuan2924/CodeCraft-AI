package ai.codecraft.infrastructure.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaskWakeupPublisherTest {
    @Test
    void commitOnlyEnqueuesDiscoveryOutsideTheCommittingThread() throws Exception {
        var delivered = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        var calls = new AtomicInteger();
        var publisher = new TaskWakeupPublisher(event -> {
            calls.incrementAndGet(); thread.set(Thread.currentThread()); delivered.countDown();
        });
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.afterCommit(TaskWakeup.Lane.COPILOT);
            assertEquals(0, calls.get());
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertTrue(thread.get() != Thread.currentThread());
        } finally { TransactionSynchronizationManager.clearSynchronization(); publisher.close(); }
    }

    @Test
    void rollbackDoesNotPublishAndShutdownRejectsHintsSafely() {
        var calls = new AtomicInteger();
        var publisher = new TaskWakeupPublisher(event -> calls.incrementAndGet());
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.afterCommit(TaskWakeup.Lane.INTERVIEWER);
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertEquals(0, calls.get());
        } finally { TransactionSynchronizationManager.clearSynchronization(); publisher.close(); }
        publisher.afterCommit(TaskWakeup.Lane.EVALUATION);
        assertEquals(0, calls.get());
    }
}
