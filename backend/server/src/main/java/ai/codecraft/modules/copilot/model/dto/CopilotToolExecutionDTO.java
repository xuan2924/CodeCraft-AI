package ai.codecraft.modules.copilot.model.dto;

public record CopilotToolExecutionDTO(boolean waiting, String body) {
    public CopilotToolExecutionDTO {
        if (waiting == (body != null)) throw new IllegalArgumentException("Tool result must be either a wait or a confirmed response");
    }
    @Override public String toString() { return "CopilotToolExecutionDTO[waiting=" + waiting + ", content=redacted]"; }
}
