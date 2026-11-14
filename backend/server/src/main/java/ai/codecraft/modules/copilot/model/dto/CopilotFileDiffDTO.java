package ai.codecraft.modules.copilot.model.dto;

public record CopilotFileDiffDTO(String path, String kind, String before, String after) {
    @Override public String toString() { return "CopilotFileDiffDTO[path=" + path + ", content=redacted]"; }
}
