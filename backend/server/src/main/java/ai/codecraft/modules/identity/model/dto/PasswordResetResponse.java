package ai.codecraft.modules.identity.model.dto;

public record PasswordResetResponse(String userId, String operationId, boolean credentialDelivered,
                                    String temporaryPassword, boolean currentSessionRevoked) {
    @Override public String toString() { return "PasswordResetResponse[REDACTED]"; }
}
