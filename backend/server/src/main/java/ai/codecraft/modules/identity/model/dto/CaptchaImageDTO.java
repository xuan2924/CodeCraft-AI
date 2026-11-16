package ai.codecraft.modules.identity.model.dto;

/** Internal rendering result; only dataUrl crosses the HTTP boundary. */
public record CaptchaImageDTO(String answer, String dataUrl) {
    @Override public String toString() { return "CaptchaImageDTO[redacted]"; }
}
