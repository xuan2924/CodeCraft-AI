package ai.codecraft.modules.identity.model.entity;

import java.time.Instant;

public record AnonymousCsrfEntity(String csrfToken, Instant expiresAt) {
    @Override public String toString() { return "AnonymousCsrfEntity[redacted]"; }
}
