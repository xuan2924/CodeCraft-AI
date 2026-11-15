package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
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
    private final CopilotProposalStore proposals;
    private final CopilotTestTools tests;
    private final CopilotCommandService commands;
    private final CopilotAutoApproval automatic;
    private final CopilotCompactionRepository contexts;
    private final CopilotCompactionExecution summaries;
    private final CopilotCompactionRunner summaryRunner;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, WorkerThread> threads = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CopilotCoordinator(CopilotTaskRepository tasks, CopilotExecutionStore store, CopilotRunner runner, CopilotProperties limits, Clock clock, CopilotProposalStore proposals, CopilotTestTools tests, CopilotAutoApproval automatic, CopilotCompactionRepository contexts, CopilotCompactionExecution summaries, CopilotCompactionRunner summaryRunner, CopilotCommandService commands) {
        this.commands = commands;
        this.tasks = tasks; this.store = store; this.runner = runner; this.clock = clock; this.proposals = proposals; this.tests = tests; this.automatic = automatic;
        this.contexts = contexts; this.summaries = summaries; this.summaryRunner = summaryRunner;
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
            for (String id : commands.automaticPending()) {
                if (!submit(id, () -> commands.approveAuto(id))) break;
            }
            for (String id : automatic.pending()) {
                if (!submit(id, () -> automatic.apply(id))) break;
            }
            for (String id : contexts.runnableStandalone()) {
                if (!submit("summary:" + id, () -> summaryRunner.runStandalone(id))) break;
            }
            for (String id : tasks.queued()) {
                if (!submit(id, () -> runner.run(id, UUID.randomUUID().toString()))) break;
            }
        } catch (RuntimeException failure) { LOG.warn("Copilot discovery unavailable ({})", failure.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        if (closed.get()) return;
        try {
            for (String id : contexts.discover()) {
                String key = summaries.recover(id);
                var thread = key == null ? null : threads.get(key);
                if (thread != null) thread.interrupt();
            }
            for (String id : tasks.expired(clock.instant())) {
                var thread = threads.get(id);
                if (thread != null) thread.interrupt();
                store.recover(id);
            }
            for (String id : tasks.idle()) { proposals.recoverTask(id); tests.recoverTask(id); commands.recoverTask(id); store.recover(id); }
        } catch (RuntimeException failure) { LOG.warn("Copilot recovery unavailable ({})", failure.getClass().getSimpleName()); }
    }

    private boolean submit(String id, Runnable work) {
        if (!active.add(id)) return true;
        try { workers.execute(() -> execute(id, work)); return true; }
        catch (RejectedExecutionException full) { active.remove(id); return false; }
    }

    private void execute(String id, Runnable work) {
        var workerThread = new WorkerThread(Thread.currentThread());
        threads.put(id, workerThread);
        try { if (!closed.get()) work.run(); }
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
