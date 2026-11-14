package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CopilotCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CopilotCoordinator.class);
    private final CopilotTaskRepository tasks;
    private final CopilotExecutionStore store;
    private final CopilotRunner runner;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, WorkerThread> threads = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CopilotCoordinator(CopilotTaskRepository tasks, CopilotExecutionStore store, CopilotRunner runner, CopilotProperties limits, Clock clock) {
        this.tasks = tasks; this.store = store; this.runner = runner; this.clock = clock;
        workers = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().name("copilot-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelay = 500)
    public void dispatch() {
        if (closed.get()) return;
        try {
            for (String id : tasks.stopping()) {
                var thread = threads.get(id);
                if (thread != null) thread.interrupt();
            }
            for (String id : tasks.queued()) {
                if (!active.add(id)) continue;
                try { workers.execute(() -> execute(id)); }
                catch (RejectedExecutionException full) { active.remove(id); break; }
            }
        } catch (RuntimeException failure) { LOG.warn("Copilot discovery unavailable ({})", failure.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        if (closed.get()) return;
        try {
            for (String id : tasks.expired(clock.instant())) {
                var thread = threads.get(id);
                if (thread != null) thread.interrupt();
                store.recover(id);
            }
            for (String id : tasks.idle()) store.recover(id);
        } catch (RuntimeException failure) { LOG.warn("Copilot recovery unavailable ({})", failure.getClass().getSimpleName()); }
    }

    private void execute(String id) {
        var workerThread = new WorkerThread(Thread.currentThread());
        threads.put(id, workerThread);
        try { if (!closed.get()) runner.run(id, UUID.randomUUID().toString()); }
        catch (RuntimeException failure) { LOG.warn("Copilot worker awaiting recovery ({})", failure.getClass().getSimpleName()); }
        finally { workerThread.clear(); threads.remove(id, workerThread); active.remove(id); }
    }

    /** Prevent a late stop from interrupting the pool thread after it has been reused by another task. */
    private static final class WorkerThread {
        private Thread thread;
        private boolean interrupted;
        WorkerThread(Thread thread) { this.thread = thread; }
        synchronized void interrupt() { if (thread != null && !interrupted) { interrupted = true; thread.interrupt(); } }
        synchronized void clear() { thread = null; }
    }

    @Override
    @PreDestroy
    public void close() {
        closed.set(true); workers.shutdownNow();
        try { if (!workers.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("Copilot workers still converging after shutdown"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
