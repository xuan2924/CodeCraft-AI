package ai.codecraft.infrastructure.tasks;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Commit callbacks only enqueue bounded/coalesced hints. Database discovery never runs in the committing thread. */
@Component
public final class TaskWakeupPublisher {
    private final ApplicationEventPublisher events;
    private final Set<TaskWakeup.Lane> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(TaskWakeup.Lane.values().length), Thread.ofPlatform().name("task-wakeup-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());

    public TaskWakeupPublisher(ApplicationEventPublisher events) { this.events = events; }

    public void afterCommit(TaskWakeup.Lane lane) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue(lane); }
            });
        } else enqueue(lane);
    }

    private void enqueue(TaskWakeup.Lane lane) {
        if (!pending.add(lane)) return;
        try {
            executor.execute(() -> {
                // Remove before discovery, allowing a concurrent commit to request one more pass.
                pending.remove(lane);
                try { events.publishEvent(new TaskWakeup(lane)); }
                catch (RuntimeException ignored) { /* Existing persistent scans recover lost hints. */ }
            });
        } catch (RejectedExecutionException closedOrFull) { pending.remove(lane); }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); pending.clear(); }
}
