package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
import java.util.List;

public record CopilotContextPreparedDTO(CopilotContextTicketDTO ticket, CopilotContextInputDTO input,
    CopilotContextPlanDTO plan, List<CopilotSteerEntity> adjustments, String configurationPayload,
    String projectRevision, String projectPayload) {
    public CopilotContextPreparedDTO(CopilotContextTicketDTO ticket, CopilotContextInputDTO input,
        CopilotContextPlanDTO plan, List<CopilotSteerEntity> adjustments, String configurationPayload) {
        this(ticket, input, plan, adjustments, configurationPayload, null, null);
    }
    public CopilotContextPreparedDTO { adjustments = List.copyOf(adjustments); }
    @Override public String toString() { return "CopilotContextPreparedDTO[REDACTED]"; }
}
