package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record PreparationAuthorizationDTO(String sessionHash, Instant notAfter) {
    @Override
    public String toString() { return "PreparationAuthorizationDTO[redacted]"; }
}
