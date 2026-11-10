package ai.codecraft.modules.workspace.model.entity;

public record TaskEventEntity(long sequence, String type, String payload, int bytes) {}
