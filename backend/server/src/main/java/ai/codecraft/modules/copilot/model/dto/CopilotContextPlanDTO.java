package ai.codecraft.modules.copilot.model.dto;

public record CopilotContextPlanDTO(CopilotContextProjectionDTO projection, boolean safe, boolean automaticNeeded,
    CopilotCompactionPlanDTO compaction) {
    @Override public String toString() { return "CopilotContextPlanDTO[REDACTED]"; }
}
