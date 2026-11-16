package ai.codecraft.modules.identity.model.dto;

public record LoginRequest(String username, String password, String captchaId, String captchaAnswer) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
