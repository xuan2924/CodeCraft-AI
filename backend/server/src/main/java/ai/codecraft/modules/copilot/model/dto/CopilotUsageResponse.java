package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import java.util.List;

public record CopilotUsageResponse(String exerciseId, CopilotUsageContextResponse context,
    List<ModelUsageTotalsResponse> totals, List<ModelCostSubtotalResponse> costs) {
    public CopilotUsageResponse { totals = List.copyOf(totals); costs = List.copyOf(costs); }
}
