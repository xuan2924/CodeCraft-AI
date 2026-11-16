package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record FeedbackEntity(String id, String exerciseId, String userId, String requestKey, String requestFingerprint, String snapshot,
    String state, String reply, String replyKey, Instant createdAt, Instant handledAt) {
    @Override public String toString() { return "FeedbackEntity[id=" + id + ", state=" + state + ", snapshot=REDACTED]"; }
}
