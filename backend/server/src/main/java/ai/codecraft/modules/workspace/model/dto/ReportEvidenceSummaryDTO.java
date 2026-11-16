package ai.codecraft.modules.workspace.model.dto;

/** Deliberately has no private evidence content field. */
public record ReportEvidenceSummaryDTO(String id, String kind, String source, String summary, boolean truncated) {}
