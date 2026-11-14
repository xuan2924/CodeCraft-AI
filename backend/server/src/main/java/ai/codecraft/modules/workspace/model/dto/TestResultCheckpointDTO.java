package ai.codecraft.modules.workspace.model.dto;

public record TestResultCheckpointDTO(String exerciseId, String state, boolean quiescent, long version, boolean retired) {}
