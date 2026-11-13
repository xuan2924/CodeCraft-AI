package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record TestStopEntity(String id, String taskId, long gateEpoch, Instant notAfter, String state, boolean ownerRecovery) {}
