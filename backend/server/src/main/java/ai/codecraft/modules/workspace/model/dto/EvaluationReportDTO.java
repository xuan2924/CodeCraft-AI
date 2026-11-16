package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record EvaluationReportDTO(String verification, Integer total, String conclusion, List<ReportDimensionDTO> dimensions) {
    public EvaluationReportDTO { dimensions = List.copyOf(dimensions); }
}
