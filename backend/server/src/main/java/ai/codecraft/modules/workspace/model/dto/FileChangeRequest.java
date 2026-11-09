package ai.codecraft.modules.workspace.model.dto;

public record FileChangeRequest(String path, String kind, String content) {}
