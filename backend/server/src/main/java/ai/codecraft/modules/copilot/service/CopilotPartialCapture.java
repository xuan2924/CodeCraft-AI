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
    private final int[] argumentBytes = new int[32]; // maximum permitted tool batch
    private String toolName = "";
    private int toolIndex = -1;
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
        publish();
        long now = nanos.getAsLong();
        if (now - lastFlush >= CHECKPOINT_NANOS) flush(now);
    }

    synchronized void tool(int index, String name, int bytes) {
        if (closed || index < 0 || index >= argumentBytes.length) return;
        toolIndex = index;
        toolName = CopilotToolCatalog.find(name) == null ? "unknown" : name;
        argumentBytes[index] += bytes;
        publish();
    }

    private void publish() {
        try {
            String payload = json.writeValueAsString(Map.of("taskId", task, "turnId", turn, "previewEpoch", epoch,
                "previewSequence", Long.toString(++sequence), "text", tail(text, 4096), "thinking", tail(thinking, 1024),
                "toolName", toolName, "toolArgumentBytes", toolIndex < 0 ? 0 : argumentBytes[toolIndex],
                "truncated", text.length() > 4096, "thinkingTruncated", thinking.length() > 1024));
            hub.preview(task, turn, ("event:copilot_preview\ndata:" + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize preview", failure); }
    }

    private static String tail(StringBuilder value, int max) {
        int start = Math.max(0, value.length() - max);
        if (start > 0 && Character.isLowSurrogate(value.charAt(start))) start++;
        return value.substring(start);
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
