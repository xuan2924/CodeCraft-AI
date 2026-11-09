package ai.codecraft.modules.identity.model.dto;

/** Internal result: the token belongs in the HttpOnly cookie, not the response body. */
public record LoginResultDTO(String token, SessionResponse identity) {
    @Override public String toString() { return "LoginResultDTO[redacted]"; }
}
