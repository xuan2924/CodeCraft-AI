package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.config.ModelCheckWorkerProperties;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The database owns pending groups; there is no unbounded in-memory work queue. */
@Component
public class ModelCheckCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ModelCheckCoordinator.class);
    private final ModelCheckRepository checks;
    private final ModelCheckRunner runner;
    private final ThreadPoolExecutor workers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ModelCheckCoordinator(ModelCheckRepository checks, ModelCheckRunner runner, ModelCheckWorkerProperties properties) {
        this.checks = checks; this.runner = runner;
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(properties.concurrency(), properties.concurrency(), 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            var thread = new Thread(runnable, "model-check-" + sequence.incrementAndGet());
            thread.setDaemon(false); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (closed.get()) return;
        try {
            for (var id : checks.queued()) {
                if (!active.add(id)) continue;
                try { workers.execute(() -> execute(id)); }
                catch (RejectedExecutionException full) { active.remove(id); break; }
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("Model check discovery unavailable ({})", unavailable.getClass().getSimpleName());
        }
    }

    private void execute(String id) {
        try { if (!closed.get()) runner.run(id); }
        catch (RuntimeException unavailable) {
            // An accepted request remains in the ledger for recovery; never retry an uncertain paid call here.
            LOG.warn("Model check worker unavailable ({})", unavailable.getClass().getSimpleName());
        } finally { active.remove(id); }
    }

    @Override
    @PreDestroy
    public void close() {
        closed.set(true);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("Model check workers still converging after shutdown");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
