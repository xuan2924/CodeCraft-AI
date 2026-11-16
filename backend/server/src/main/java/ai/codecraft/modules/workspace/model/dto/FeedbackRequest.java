package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record FeedbackRequest(String targetType, String targetId, long generation, String description, List<String> evidenceIds, String previewFingerprint) {}
