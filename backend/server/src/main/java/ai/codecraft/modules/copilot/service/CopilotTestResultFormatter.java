package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotTestResultDTO;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Bounded model projection, not an execution authority or a replacement for the original test record. */
@Component
public class CopilotTestResultFormatter {
    private static final Set<String> TERMINAL = Set.of("PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    private static final int LOG_BYTES = 16384;
    private static final int LOG_SEGMENTS = 64;

    public CopilotTestResultDTO format(TestTaskResponse source, boolean reused) {
        if (source == null || !source.quiescent() || source.finishedAt() == null || source.state() == null || !TERMINAL.contains(source.state())) {
            throw new IllegalStateException("Test execution is not confirmed and quiescent");
        }
        if (source.logs() == null || source.logs().size() > 4096) throw new IllegalStateException("Invalid stored test logs");
        long total = 0;
        for (var log : source.logs()) {
            if (log == null || log.text() == null || !("stdout".equals(log.channel()) || "stderr".equals(log.channel()))) {
                throw new IllegalStateException("Invalid stored test log segment");
            }
            total += log.text().getBytes(StandardCharsets.UTF_8).length;
            // Visible NUL/replacement characters can expand the original 1 MiB byte log threefold.
            if (total > 3 * 1024 * 1024) throw new IllegalStateException("Stored test logs exceed their bound");
        }
        var retained = new ArrayList<TestLogResponse>();
        int remaining = LOG_BYTES;
        for (int index = source.logs().size() - 1; index >= 0 && retained.size() < LOG_SEGMENTS; index--) {
            var log = source.logs().get(index);
            byte[] bytes = log.text().getBytes(StandardCharsets.UTF_8);
            int start = Math.max(0, bytes.length - remaining);
            while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
            retained.add(new TestLogResponse(log.channel(), new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)));
            remaining -= bytes.length - start;
            if (start > 0 || remaining == 0) break;
        }
        long omitted = total - (LOG_BYTES - remaining);
        var result = new TestTaskResponse(source.taskId(), source.exerciseId(), source.executionInstanceId(), source.workspaceInstanceId(), source.revision(),
            source.target(), source.state(), source.reason(), source.startedAt(), source.finishedAt(), source.exitCode(), true, source.cleanup(), source.copyCleanup(),
            source.droppedBytes(), source.logsIncomplete(), List.copyOf(retained.reversed()), source.problem(), source.evidence());
        return new CopilotTestResultDTO("TEST_RESULT", reused, result, omitted > 0 || retained.size() < source.logs().size(), Long.toString(omitted));
    }
}
