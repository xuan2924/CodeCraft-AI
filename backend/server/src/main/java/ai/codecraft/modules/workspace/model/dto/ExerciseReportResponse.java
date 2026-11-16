package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record ExerciseReportResponse(String exerciseId, String reportId, long generation, String state, String finalRevision,
    String verification, String finalTestId, Boolean reviewSkipped, String platformVersionId, int attempts, String reason,
    EvaluationReportDTO evaluation, List<ReportEvidenceSummaryDTO> evidence) {
    public ExerciseReportResponse { evidence = List.copyOf(evidence); }
}
