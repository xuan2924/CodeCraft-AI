package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record FeedbackResponse(String id, String snapshot, String state, String reply, Instant createdAt, Instant handledAt) {}
