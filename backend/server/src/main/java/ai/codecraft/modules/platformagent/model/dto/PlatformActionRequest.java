package ai.codecraft.modules.platformagent.model.dto;

public record PlatformActionRequest(String expectedRevision, String expectedRoleRevision, Boolean feeAcknowledged, String apiKey) {
    @Override public String toString() { return "PlatformActionRequest[REDACTED]"; }
}
