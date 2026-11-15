package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import java.util.List;

/** Authorizes private preparation only; dispatch must revalidate every mutable checkpoint. */
public record CopilotContextTicketDTO(String taskId, String exerciseId, String sessionHash, String workerId,
    long version, String summaryId, long throughSequence, long highWater, ModelInvocationDTO target,
    List<String> steerIds, long textHistoryThroughSequence) {
    public CopilotContextTicketDTO { steerIds = List.copyOf(steerIds); }
    public boolean textHistory() { return textHistoryThroughSequence > throughSequence; }
    @Override public String toString() { return "CopilotContextTicketDTO[REDACTED]"; }
}
