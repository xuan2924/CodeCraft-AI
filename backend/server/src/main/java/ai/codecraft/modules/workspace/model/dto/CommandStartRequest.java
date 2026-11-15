package ai.codecraft.modules.workspace.model.dto;

public record CommandStartRequest(String command, String directory, String expectedRevision) {
    @Override public String toString() { return "CommandStartRequest[REDACTED]"; }
}
