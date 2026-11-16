package ai.codecraft.modules.platformagent.model.entity;

import ai.codecraft.modules.platformagent.model.PlatformRole;
import java.time.Instant;

public record PlatformVersionEntity(String id, PlatformRole role, String definition, String state, long revision,
    long credentialRevision, String credentialKeyId, String credentialNonce, String credentialCiphertext, Instant createdAt, Instant publishedAt) {
    @Override public String toString() { return "PlatformVersionEntity[REDACTED]"; }
}
