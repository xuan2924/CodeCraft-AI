package ai.codecraft.modules.workspace.model.dto;

import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import java.time.Instant;

public record InterviewerDispatchDTO(String id, String exerciseId, String question, String phase,
    PlatformVersionEntity version, Instant deadline) {}
