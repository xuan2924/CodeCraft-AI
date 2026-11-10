package ai.codecraft.modules.workspace.model.entity;

public record TaskStreamEntity(String taskId, String streamId, long sequence, long firstSequence) {}
