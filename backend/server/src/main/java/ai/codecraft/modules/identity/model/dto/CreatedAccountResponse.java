package ai.codecraft.modules.identity.model.dto;

public record CreatedAccountResponse(String userId, String operationId, boolean credentialDelivered,
                                     String temporaryPassword) {
    @Override public String toString() { return "CreatedAccountResponse[redacted]"; }
}
