package ai.codecraft.infrastructure.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Bounded, lossy hints only. Redis failure never changes PostgreSQL recovery or task ownership. */
public final class TaskRealtimeBus {
    public static final String CHANNEL = "codecraft:task-hints:v1";
    private final String node = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper();
    private final StringRedisTemplate redis;
    private final CopyOnWriteArrayList<Consumer<Hint>> listeners = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), Thread.ofPlatform().name("task-bus-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public TaskRealtimeBus(StringRedisTemplate redis) { this.redis = redis; }

    public AutoCloseable subscribe(Consumer<Hint> listener) {
        if (listeners.size() >= 128) throw new IllegalStateException("Hint subscription capacity reached");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(Hint hint) {
        deliver(hint);
        if (redis == null) return;
        try {
            sender.execute(() -> {
                try { redis.convertAndSend(CHANNEL, json.writeValueAsString(new Envelope(node, hint))); }
                catch (Exception failure) { dropped(); }
            });
        } catch (RuntimeException full) { dropped(); }
    }

    public void receive(byte[] body) {
        if (body.length > 65536) { dropped(); return; }
        try {
            Envelope envelope = json.readValue(body, Envelope.class);
            if (!node.equals(envelope.node())) deliver(envelope.hint());
        } catch (Exception invalid) { dropped(); }
    }

    private void deliver(Hint hint) {
        for (var listener : listeners) {
            try { listener.accept(hint); }
            catch (RuntimeException failure) { dropped(); }
        }
    }

    private static void dropped() {
        LoggerFactory.getLogger(TaskRealtimeBus.class).warn("Task hint dropped; persistent scan will recover");
    }

    @PreDestroy
    public void close() { sender.shutdownNow(); listeners.clear(); }

    public record Envelope(String node, Hint hint) { }
    public record Hint(String kind, String task, String turn, byte[] frame) {
        public Hint {
            if (!java.util.Set.of("preview", "clear", "signal", "wakeup").contains(kind)
                || task == null || task.length() > 128 || (frame != null && frame.length > 32768)) {
                throw new IllegalArgumentException("Invalid task hint");
            }
            frame = frame == null ? null : frame.clone();
        }
        @Override public byte[] frame() { return frame == null ? null : frame.clone(); }
    }
}
