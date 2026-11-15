package ai.codecraft.modules.copilot.model.dto;

import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;

public record CopilotCompactionPlanDTO(long throughSequence, List<String> sourceIds, ChatRequest request,
    long estimatedTokens, long replacedBytes, int outputTokens, int maximumSummaryBytes) {
    public CopilotCompactionPlanDTO { sourceIds = List.copyOf(sourceIds); }
    @Override public String toString() { return "CopilotCompactionPlanDTO[REDACTED]"; }
}
