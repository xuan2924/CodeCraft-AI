package ai.codecraft.modules.identity.model.entity;

import java.time.Instant;

public record RevocationIntentEntity(String id, String actorId, String targetId, String actorSessionHash,
                                     String scopeSessionHash, String kind, String requestKey, String fingerprint,
                                     String state, Instant createdAt, String preparationIds) {
    @Override
    public String toString() { return "RevocationIntentEntity[id=" + id + ", state=" + state + "]"; }
}
