package ai.codecraft.infrastructure.stream;

import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded local view of lossy bus hints; authorization and durable recovery remain in the stream source. */
@Component
public final class TransientTaskStreamHub {
    private static final int MAX_TASKS = 128;
    private static final int MAX_PREVIEW_BYTES = 32768;
    private static final int MAX_SUBSCRIBERS = 128;
    private final LinkedHashMap<String, Preview> previews = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    private final TaskRealtimeBus bus;

    public TransientTaskStreamHub() { this(new TaskRealtimeBus(null)); }

    @org.springframework.beans.factory.annotation.Autowired
    public TransientTaskStreamHub(TaskRealtimeBus bus) {
        this.bus = bus;
        bus.subscribe(hint -> {
            switch (hint.kind()) {
                case "preview" -> acceptPreview(hint.task(), hint.turn(), hint.frame());
                case "clear" -> { synchronized (this) { previews.remove(hint.task()); } notifyListeners(hint.task()); }
                case "signal" -> notifyListeners(hint.task());
                default -> { }
            }
        });
    }

    public synchronized AutoCloseable subscribe(Consumer<String> listener) {
        if (listeners.size() >= MAX_SUBSCRIBERS) throw new IllegalStateException("Stream notification capacity reached");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Payload is a complete bounded SSE frame, without an id: transient sequence is not a cursor. */
    public void preview(String task, String turn, byte[] frame) {
        if (frame.length > MAX_PREVIEW_BYTES) throw new IllegalArgumentException("Preview exceeds bound");
        bus.publish(new TaskRealtimeBus.Hint("preview", task, turn, frame));
    }

    private void acceptPreview(String task, String turn, byte[] frame) {
        synchronized (this) {
            if (!previews.containsKey(task) && previews.size() >= MAX_TASKS) previews.pollFirstEntry();
            previews.putLast(task, new Preview(turn, sequence.incrementAndGet(), frame));
        }
        notifyListeners(task);
    }

    public synchronized Preview latest(String task) { return previews.get(task); }

    public void clear(String task) {
        bus.publish(new TaskRealtimeBus.Hint("clear", task, null, null));
    }

    public void afterCommit(String task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { signal(task); }
            });
        } else signal(task);
    }

    public void signal(String task) { bus.publish(new TaskRealtimeBus.Hint("signal", task, null, null)); }

    private void notifyListeners(String task) {
        for (Consumer<String> listener : listeners) {
            try { listener.accept(task); }
            catch (RuntimeException ignored) { /* A dropped hint is recovered by the idle authoritative scan. */ }
        }
    }

    public record Preview(String turnId, long sequence, byte[] frame) {
        public Preview { frame = frame.clone(); }
        @Override public byte[] frame() { return frame.clone(); }
    }
}
