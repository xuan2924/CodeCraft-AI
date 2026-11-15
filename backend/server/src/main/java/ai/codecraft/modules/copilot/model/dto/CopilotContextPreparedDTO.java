package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
import java.util.List;

public record CopilotContextPreparedDTO(CopilotContextTicketDTO ticket, CopilotContextInputDTO input,
    CopilotContextPlanDTO plan, List<CopilotSteerEntity> adjustments, String configurationPayload) {
    public CopilotContextPreparedDTO { adjustments = List.copyOf(adjustments); }
    @Override public String toString() { return "CopilotContextPreparedDTO[REDACTED]"; }
}
