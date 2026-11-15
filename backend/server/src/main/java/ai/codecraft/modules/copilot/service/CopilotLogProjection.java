package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotLogExcerptDTO;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A UTF-8-safe model excerpt shared by tests and development commands; original evidence stays intact. */
final class CopilotLogProjection {
    private static final int LOG_BYTES = 16384;
    private static final int LOG_SEGMENTS = 64;
    private CopilotLogProjection() {}
    static CopilotLogExcerptDTO tail(List<TestLogResponse> logs) {
        if (logs == null || logs.size() > 4096) throw new IllegalStateException("Invalid stored execution logs");
        long total = 0;
        for (var log : logs) {
            if (log == null || log.text() == null || !("stdout".equals(log.channel()) || "stderr".equals(log.channel()))) {
                throw new IllegalStateException("Invalid stored execution log segment");
            }
            total += log.text().getBytes(StandardCharsets.UTF_8).length;
            // Visible NUL/replacement characters can expand the original byte log threefold.
            if (total > 3 * 1024 * 1024) throw new IllegalStateException("Stored execution logs exceed their bound");
        }
        var retained = new ArrayList<TestLogResponse>();
        int remaining = LOG_BYTES;
        for (int index = logs.size() - 1; index >= 0 && retained.size() < LOG_SEGMENTS; index--) {
            var log = logs.get(index);
            byte[] bytes = log.text().getBytes(StandardCharsets.UTF_8);
            int start = Math.max(0, bytes.length - remaining);
            while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
            retained.add(new TestLogResponse(log.channel(), new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)));
            remaining -= bytes.length - start;
            if (start > 0 || remaining == 0) break;
        }
        long omitted = total - (LOG_BYTES - remaining);
        return new CopilotLogExcerptDTO(List.copyOf(retained.reversed()), omitted > 0 || retained.size() < logs.size(), Long.toString(omitted));
    }
}
