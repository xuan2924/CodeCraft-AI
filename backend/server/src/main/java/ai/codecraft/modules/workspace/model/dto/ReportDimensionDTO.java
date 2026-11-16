package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record ReportDimensionDTO(String key, Integer score, String feedback, List<String> evidenceIds) {
    public ReportDimensionDTO { evidenceIds = List.copyOf(evidenceIds); }
}
