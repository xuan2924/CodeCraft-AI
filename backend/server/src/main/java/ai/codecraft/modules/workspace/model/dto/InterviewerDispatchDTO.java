package ai.codecraft.modules.workspace.model.dto;

import ai.codecraft.modules.platformagent.model.dto.PlatformBindingDTO;
import java.time.Instant;

public record InterviewerDispatchDTO(String id, String exerciseId, String question, String phase, String stage, Long revision, String mode, String topic, int hintLevel, String sessionHash,
    PlatformBindingDTO version, Instant deadline) {
    @Override public String toString() { return "InterviewerDispatchDTO[REDACTED]"; }
}
