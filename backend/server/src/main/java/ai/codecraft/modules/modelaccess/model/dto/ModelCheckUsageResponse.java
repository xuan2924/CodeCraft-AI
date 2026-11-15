package ai.codecraft.modules.modelaccess.model.dto;

import java.util.List;

public record ModelCheckUsageResponse(String checkId, List<ModelUsageTotalsResponse> totals, List<ModelCostSubtotalResponse> costs) {
    public ModelCheckUsageResponse { totals = List.copyOf(totals); costs = List.copyOf(costs); }
}
