package ai.codecraft.modules.identity.model.dto;

public record LoginRequest(String username, String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
