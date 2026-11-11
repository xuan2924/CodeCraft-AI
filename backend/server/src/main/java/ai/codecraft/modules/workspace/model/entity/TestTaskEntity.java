package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record TestTaskEntity(String id, String executionInstanceId, String exerciseId, String workspaceInstanceId,
    long revision, long gateEpoch, Instant notAfter, String target, String state, boolean dispatched,
    boolean quiescent, String snapshot, String hostEpoch, long stateVersion) {}
