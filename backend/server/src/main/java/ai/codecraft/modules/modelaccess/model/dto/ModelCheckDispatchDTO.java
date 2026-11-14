package ai.codecraft.modules.modelaccess.model.dto;

import java.time.Instant;

/** Worker-local single-use dispatch result. Never an HTTP response or persisted credential snapshot. */
public record ModelCheckDispatchDTO(String requestId, String checkId, int ordinal, Instant notAfter,
    int remainingDecodedBytes, ModelInvocationDTO invocation) {
    @Override public String toString() { return "ModelCheckDispatchDTO[redacted]"; }
}
