package ai.codecraft.modules.workspace.stream;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.IdentityRevokedEventDTO;
import ai.codecraft.modules.workspace.config.TaskStreamProperties;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.TaskStreamStore;
import ai.codecraft.modules.workspace.service.TaskStreamSource;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nonblocking Servlet adapter. No per-client event queue and no database lock during writes. */
@Component
public final class TaskEventTransport {
    private final TaskStreamStore store;
    private final TaskStreamProperties limits;
    private final Set<Connection> connections = new HashSet<>();
    private final ThreadPoolExecutor workers;
    public TaskEventTransport(TaskStreamStore store, TaskStreamProperties limits) {
        this.store = store; this.limits = limits;
        workers = new ThreadPoolExecutor(limits.connections(), limits.connections(), 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.connections()), Thread.ofPlatform().name("task-stream-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    public void open(String session, String exercise, String task, String after, String lastEvent,
        HttpServletRequest request, HttpServletResponse response) throws IOException {
        open(session, exercise, task, after, lastEvent, request, response, store::batch);
    }
    public void open(String session, String exercise, String task, String after, String lastEvent,
        HttpServletRequest request, HttpServletResponse response, TaskStreamSource source) throws IOException {
        if (after != null && lastEvent != null && !after.equals(lastEvent)) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        String cursor = lastEvent != null ? lastEvent : after;
        TaskStreamBatchDTO authorized = source.batch(session, exercise, task, cursor, false);
        Connection connection = new Connection(session, exercise, task, authorized.userId(), authorized.cursor(), source);
        synchronized (connections) {
            if (connections.size() >= limits.connections() || connections.stream().filter(value -> value.user.equals(authorized.userId())).count() >= limits.perUser()) {
                throw new BusinessException(WorkspaceErrorCode.STREAM_CAPACITY);
            }
            connections.add(connection);
        }
        try {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            connection.start(request.startAsync(), response.getOutputStream());
        } catch (RuntimeException | IOException error) { connection.close(); throw error; }
    }
    @Scheduled(fixedDelayString = "${workspace.stream.poll-millis:1000}")
    public void poll() {
        for (Connection connection : snapshot()) connection.schedule();
    }
    @EventListener
    public void revoked(IdentityRevokedEventDTO event) {
        for (Connection connection : snapshot()) {
            if (connection.user.equals(event.userId()) && (event.sessionHash() == null || connection.session.equals(event.sessionHash()))) connection.close();
        }
    }
    private Connection[] snapshot() { synchronized (connections) { return connections.toArray(Connection[]::new); } }
    @PreDestroy
    public void shutdown() {
        for (Connection connection : snapshot()) connection.close();
        workers.shutdownNow();
    }
    private final class Connection implements WriteListener, AsyncListener {
        private final String session;
        private final String exercise;
        private final String task;
        private final String user;
        private final TaskStreamSource source;
        private final AtomicBoolean busy = new AtomicBoolean();
        private String cursor;
        private AsyncContext context;
        private ServletOutputStream output;
        private boolean closed;
        private boolean started;
        private long lastWrite;
        private long stalledSince;
        Connection(String session, String exercise, String task, String user, String cursor, TaskStreamSource source) {
            this.session = session; this.exercise = exercise; this.task = task; this.user = user; this.cursor = cursor; this.source = source;
        }
        synchronized void start(AsyncContext context, ServletOutputStream output) {
            this.context = context; this.output = output;
            context.setTimeout(TimeUnit.MINUTES.toMillis(30));
            context.addListener(this);
            if (closed) { context.complete(); return; }
            output.setWriteListener(this);
        }
        void schedule() {
            if (!busy.compareAndSet(false, true)) return;
            try { workers.execute(() -> { try { tick(); } finally { busy.set(false); } }); }
            catch (RuntimeException rejected) { busy.set(false); close(); }
        }
        private synchronized void tick() {
            if (closed || !started) return;
            try {
                boolean ready = output.isReady();
                // Idle and stalled connections still recheck authoritative identity.
                TaskStreamBatchDTO batch = source.batch(session, exercise, task, cursor, ready);
                if (batch.bytes().length > 64 * 1024 || !user.equals(batch.userId())) { close(); return; }
                long now = System.nanoTime();
                if (!ready) {
                    if (stalledSince == 0) stalledSince = now;
                    if (now - stalledSince >= TimeUnit.MILLISECONDS.toNanos(limits.stalledMillis())) close();
                    return;
                }
                stalledSince = 0;
                if (batch.bytes().length > 0) {
                    output.write(batch.bytes()); cursor = batch.cursor(); lastWrite = now;
                } else if (lastWrite == 0 || now - lastWrite >= TimeUnit.MILLISECONDS.toNanos(limits.heartbeatMillis())) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); lastWrite = now;
                }
                // Nonblocking flush makes even an idle initial response visible.
                if (output.isReady()) output.flush();
            } catch (BusinessException expired) {
                if (expired.code().equals("CURSOR_EXPIRED")) {
                    try { if (output.isReady()) output.write("event:reset_required\ndata:{}\n\n".getBytes(StandardCharsets.UTF_8)); }
                    catch (IOException | IllegalStateException ignored) { /* Best effort control frame only. */ }
                }
                close();
            } catch (IOException | RuntimeException failure) { close(); }
        }
        synchronized void close() {
            if (closed) return;
            closed = true;
            synchronized (connections) { connections.remove(this); }
            if (context != null) { try { context.complete(); } catch (IllegalStateException ignored) { /* Container already completed. */ } }
        }
        @Override public synchronized void onWritePossible() { started = true; schedule(); }
        @Override public void onError(Throwable error) { close(); }
        @Override public void onComplete(AsyncEvent event) { close(); }
        @Override public void onTimeout(AsyncEvent event) { close(); }
        @Override public void onError(AsyncEvent event) { close(); }
        @Override public void onStartAsync(AsyncEvent event) { close(); }
    }
}
