package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;
import java.util.List;

public record CopilotTelemetryResponse(Totals totals, List<Task> recentTasks) {
    public record Totals(long taskCount, long rounds, long tools, Long modelElapsedMs, Long toolElapsedMs,
        long readWindows, Long readSlotsUsed, long exploreChildren, long microCompactedRounds, Long microSavedBytes,
        long recoveredRevisionConflicts, long steerInterruptions, Long chatInputTokens, Long chatOutputTokens,
        Long chatCacheReadTokens, Long chatCacheWriteTokens) {
        public Double getReadWindowUtilization() {
            return readWindows == 0 || readSlotsUsed == null ? null : readSlotsUsed / (4.0 * readWindows);
        }
    }

    public record Task(String taskId, String state, Instant createdAt, long rounds, long tools, Long modelElapsedMs,
        Long toolElapsedMs, Long microSavedBytes) { }
}
