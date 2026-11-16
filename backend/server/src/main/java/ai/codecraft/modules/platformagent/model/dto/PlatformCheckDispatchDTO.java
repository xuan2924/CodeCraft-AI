package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import java.time.Instant;

public record PlatformCheckDispatchDTO(String id, PlatformRole role, ModelInvocationDTO target, String instructions, String price, Instant deadline) {
    @Override public String toString() { return "PlatformCheckDispatchDTO[REDACTED]"; }
}
