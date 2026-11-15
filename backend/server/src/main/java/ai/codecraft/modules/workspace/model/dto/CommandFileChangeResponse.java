package ai.codecraft.modules.workspace.model.dto;

public record CommandFileChangeResponse(String kind, String path, String previousContent, String content) {
    @Override public String toString() { return "CommandFileChangeResponse[REDACTED]"; }
}
