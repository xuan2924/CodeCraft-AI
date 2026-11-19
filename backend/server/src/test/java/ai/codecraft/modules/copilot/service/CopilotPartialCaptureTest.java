package ai.codecraft.modules.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CopilotPartialCaptureTest {
    @Test
    void chunksWakeImmediatelyButCheckpointOnlyAtTwoSecondsAndCompletion() {
        var nanos = new AtomicLong();
        var hub = new TransientTaskStreamHub();
        var writes = new ArrayList<String>();
        try (var capture = new CopilotPartialCapture("task", "turn", hub, new ObjectMapper(),
            (text, thinking) -> writes.add(text + "/" + thinking), 20000, nanos::get)) {
            for (int index = 0; index < 100; index++) capture.text("a");
            assertTrue(writes.isEmpty());
            String frame = new String(hub.latest("task").frame(), StandardCharsets.UTF_8);
            assertTrue(frame.contains("\"previewSequence\":\"100\""));
            assertFalse(frame.contains("\nid:"));
            nanos.set(1_999_999_999L);
            capture.thinking("reason");
            assertTrue(writes.isEmpty());
            nanos.incrementAndGet();
            capture.text("b");
            assertEquals(1, writes.size());
            capture.text("c");
        }
        assertEquals(2, writes.size());
        assertTrue(writes.getLast().endsWith("bc/reason"));
        assertNull(hub.latest("task"));
    }

    @Test
    void exceptionBoundaryFlushesAndHighlyEscapedPreviewRemainsBounded() throws Exception {
        var hub = new TransientTaskStreamHub();
        var writes = new ArrayList<String>();
        try {
            try (var capture = new CopilotPartialCapture("task", "turn", hub, new ObjectMapper(),
                (text, thinking) -> writes.add(text), 20000, () -> 0)) {
                capture.text("\u0001".repeat(6000));
                capture.thinking("\u0001".repeat(6000));
                assertTrue(hub.latest("task").frame().length <= 32768);
                assertTrue(new String(hub.latest("task").frame(), StandardCharsets.UTF_8).contains("\"truncated\":true"));
                capture.text("latest");
                capture.thinking("reason");
                capture.tool(0, "edit", 3);
                capture.tool(1, "write", 8);
                capture.tool(0, "edit", 4);
                String frame = new String(hub.latest("task").frame(), StandardCharsets.UTF_8);
                assertTrue(frame.contains("latest") && frame.contains("reason"));
                assertTrue(frame.contains("\"toolName\":\"edit\"") && frame.contains("\"toolArgumentBytes\":7"));
                throw new IllegalStateException("provider interrupted");
            }
        } catch (IllegalStateException expected) { assertEquals("provider interrupted", expected.getMessage()); }
        assertEquals(1, writes.size());
        assertNull(hub.latest("task"));
    }
}
