package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.util.List;

public record CopilotContextInputDTO(String summaryId, String summaryText, long throughSequence,
    List<CopilotMessageEntity> messages, List<CopilotMessageEntity> goals, List<String> pendingUserPayloads,
    boolean hasMore, long textHistoryThroughSequence) {
    public CopilotContextInputDTO {
        messages = List.copyOf(messages); goals = List.copyOf(goals); pendingUserPayloads = List.copyOf(pendingUserPayloads);
    }
    @Override public String toString() { return "CopilotContextInputDTO[REDACTED]"; }
}
