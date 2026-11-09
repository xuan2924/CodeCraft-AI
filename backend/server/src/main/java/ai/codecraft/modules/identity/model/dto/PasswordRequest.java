package ai.codecraft.modules.identity.model.dto;

public record PasswordRequest(String currentPassword, String newPassword) {
    @Override public String toString() { return "PasswordRequest[redacted]"; }
}
