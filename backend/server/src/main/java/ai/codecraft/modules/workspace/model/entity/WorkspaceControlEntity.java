package ai.codecraft.modules.workspace.model.entity;

public record WorkspaceControlEntity(String exerciseId, long generation, Long gateEpoch, String gateState,
                                     String credentialHash, String transitionId, String fenceId) {
    @Override public String toString() { return "WorkspaceControlEntity[exerciseId=" + exerciseId + "]"; }
}
