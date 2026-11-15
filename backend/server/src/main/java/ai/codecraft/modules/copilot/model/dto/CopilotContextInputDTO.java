package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.util.List;

public record CopilotContextInputDTO(String summaryId, String summaryText, long throughSequence,
    List<CopilotMessageEntity> messages, List<CopilotMessageEntity> goals, List<String> pendingUserPayloads,
    boolean hasMore, long textHistoryThroughSequence, List<String> skillPayloads, List<String> projectPayloads, String lane) {
    public CopilotContextInputDTO(String summaryId, String summaryText, long throughSequence, List<CopilotMessageEntity> messages,
        List<CopilotMessageEntity> goals, List<String> pendingUserPayloads, boolean hasMore, long textHistoryThroughSequence,
        List<String> skillPayloads, List<String> projectPayloads) {
        this(summaryId, summaryText, throughSequence, messages, goals, pendingUserPayloads, hasMore, textHistoryThroughSequence, skillPayloads, projectPayloads, "MAIN");
    }
    public CopilotContextInputDTO(String summaryId, String summaryText, long throughSequence, List<CopilotMessageEntity> messages,
        List<CopilotMessageEntity> goals, List<String> pendingUserPayloads, boolean hasMore, long textHistoryThroughSequence, List<String> skillPayloads) {
        this(summaryId, summaryText, throughSequence, messages, goals, pendingUserPayloads, hasMore, textHistoryThroughSequence, skillPayloads, List.of());
    }
    public CopilotContextInputDTO(String summaryId, String summaryText, long throughSequence, List<CopilotMessageEntity> messages,
        List<CopilotMessageEntity> goals, List<String> pendingUserPayloads, boolean hasMore, long textHistoryThroughSequence) {
        this(summaryId, summaryText, throughSequence, messages, goals, pendingUserPayloads, hasMore, textHistoryThroughSequence, List.of());
    }
    public CopilotContextInputDTO {
        messages = List.copyOf(messages); goals = List.copyOf(goals); pendingUserPayloads = List.copyOf(pendingUserPayloads); skillPayloads = List.copyOf(skillPayloads); projectPayloads = List.copyOf(projectPayloads);
    }
    @Override public String toString() { return "CopilotContextInputDTO[REDACTED]"; }
}
