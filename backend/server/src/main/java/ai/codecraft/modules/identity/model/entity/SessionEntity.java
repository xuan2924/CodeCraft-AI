package ai.codecraft.modules.identity.model.entity;

import java.time.Instant;

public record SessionEntity(String tokenHash, String userId, long authVersion, String csrfToken,
                            Instant issuedAt, Instant lastActivity, boolean revoked) {
    @Override public String toString() { return "SessionEntity[redacted]"; }
}
