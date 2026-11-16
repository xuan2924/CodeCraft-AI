package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

/** Exact visible report generation, projected for PDF without code or conversation bodies. */
public record ReportDocumentDTO(String exerciseId, String reportId, long generation, String finalRevision,
    String verification, String state, boolean reviewSkipped, EvaluationReportDTO evaluation, List<ReportEvidenceSummaryDTO> evidence) {
    public ReportDocumentDTO { evidence = List.copyOf(evidence); }
}
