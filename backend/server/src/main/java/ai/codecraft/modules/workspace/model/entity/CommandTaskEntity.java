package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record CommandTaskEntity(String id, String exerciseId, String userId, String workspaceInstanceId,
    String executionInstanceId, String sessionHash, long actorAuthVersion, String requestKey, String fingerprint,
    String commandText, String workingDirectory, long revision, long gateEpoch, Instant notAfter, String hostEpoch,
    String state, boolean dispatched, boolean stopRequested, boolean quiescent, String synchronization,
    String snapshot, long stateVersion, boolean pruning, boolean pruned, Instant createdAt, Instant completedAt) {
    @Override public String toString() { return "CommandTaskEntity[id=" + id + ", state=" + state + "]"; }
}
