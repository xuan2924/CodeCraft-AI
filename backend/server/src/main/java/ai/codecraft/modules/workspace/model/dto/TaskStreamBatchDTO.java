package ai.codecraft.modules.workspace.model.dto;

public record TaskStreamBatchDTO(String userId, String cursor, byte[] bytes) {}
