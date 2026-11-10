package ai.codecraft.modules.workspace.model.dto;

/** Execution identity plus kind locates the retained artifact; logs are not evidence. */
public record TestEvidenceResponse(String kind, String sha256, long bytes) {}
