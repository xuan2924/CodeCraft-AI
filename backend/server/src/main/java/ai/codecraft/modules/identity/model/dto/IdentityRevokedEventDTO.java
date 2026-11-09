package ai.codecraft.modules.identity.model.dto;

/** Committed revocation hint. A null sessionHash means all sessions of the user. */
public record IdentityRevokedEventDTO(String userId, String sessionHash) {
    @Override public String toString() { return "IdentityRevokedEventDTO[REDACTED]"; }
}
