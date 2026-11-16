package ai.codecraft.modules.workspace.model.dto;

/** Content is private evaluator input; summary alone belongs in the PDF projection. */
public record ReportEvidenceDTO(String id, String kind, String source, String summary, String content, boolean truncated) {}
