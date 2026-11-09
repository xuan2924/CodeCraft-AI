package ai.codecraft.modules.identity.model.dto;

public record CsrfResponse(String token) {
    @Override public String toString() { return "CsrfResponse[redacted]"; }
}
