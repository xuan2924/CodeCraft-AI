package ai.codecraft.modules.copilot.service;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Bounded ephemeral previews and at most one durable checkpoint per two seconds, plus close. */
final class CopilotPartialCapture implements AutoCloseable {
    private static final long CHECKPOINT_NANOS = 2_000_000_000L;
    private final String task;
    private final String turn;
    private final String epoch = UUID.randomUUID().toString();
    private final TransientTaskStreamHub hub;
    private final ObjectMapper json;
    private final BiConsumer<String, String> checkpoint;
    private final LongSupplier nanos;
    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long lastFlush;
    private long sequence;
    private int persisted;
    private boolean closed;

    CopilotPartialCapture(String task, String turn, TransientTaskStreamHub hub, ObjectMapper json,
        BiConsumer<String, String> checkpoint, int maxChars) {
        this(task, turn, hub, json, checkpoint, maxChars, System::nanoTime);
    }

    CopilotPartialCapture(String task, String turn, TransientTaskStreamHub hub, ObjectMapper json,
        BiConsumer<String, String> checkpoint, int maxChars, LongSupplier nanos) {
        this.task = task; this.turn = turn; this.hub = hub; this.json = json;
        this.checkpoint = checkpoint; this.maxChars = maxChars; this.nanos = nanos;
        lastFlush = nanos.getAsLong();
    }

    synchronized void text(String chunk) { append(text, chunk); }
    synchronized void thinking(String chunk) { append(thinking, chunk); }

    private void append(StringBuilder destination, String chunk) {
        if (closed || chunk == null || chunk.isEmpty()) return;
        if ((long) text.length() + thinking.length() + chunk.length() > maxChars) {
            throw new IllegalArgumentException("Partial response exceeds bound");
        }
        destination.append(chunk);
        try {
            String payload = json.writeValueAsString(Map.of("taskId", task, "turnId", turn, "previewEpoch", epoch,
                "previewSequence", Long.toString(++sequence), "text", prefix(text, 4096), "thinking", prefix(thinking, 1024),
                "truncated", text.length() > 4096, "thinkingTruncated", thinking.length() > 1024));
            hub.preview(task, turn, ("event:copilot_preview\ndata:" + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize preview", failure); }
        long now = nanos.getAsLong();
        if (now - lastFlush >= CHECKPOINT_NANOS) flush(now);
    }

    private static String prefix(StringBuilder value, int max) {
        int end = Math.min(value.length(), max);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private void flush(long now) {
        int size = text.length() + thinking.length();
        if (size != persisted) {
            checkpoint.accept(text.toString(), thinking.toString());
            persisted = size;
        }
        lastFlush = now;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { flush(nanos.getAsLong()); }
        finally { hub.clear(task); }
    }
}
