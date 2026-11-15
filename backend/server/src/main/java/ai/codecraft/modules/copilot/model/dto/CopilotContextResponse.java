package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotContextResponse(String exerciseId, String version, String summaryId, String throughSequence,
    String summary, List<CopilotCompactionResponse> requests) {
    public CopilotContextResponse { requests = List.copyOf(requests); }
    @Override public String toString() { return "CopilotContextResponse[exercise=" + exerciseId + ", version=" + version + "]"; }
}
