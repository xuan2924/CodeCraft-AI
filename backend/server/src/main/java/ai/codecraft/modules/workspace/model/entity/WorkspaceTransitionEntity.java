package ai.codecraft.modules.workspace.model.entity;

public record WorkspaceTransitionEntity(String id, String exerciseId, String kind, Long expectedEpoch,
                                        String phase, String targetHash) {
    @Override public String toString() { return "WorkspaceTransitionEntity[id=" + id + ", phase=" + phase + "]"; }
}
