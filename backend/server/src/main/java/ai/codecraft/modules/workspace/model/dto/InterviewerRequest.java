package ai.codecraft.modules.workspace.model.dto;

public record InterviewerRequest(String text, String mode, String topic) {
    public InterviewerRequest {
        mode = mode == null ? "CLARIFY" : mode;
        topic = topic == null ? "general" : topic;
    }
    public InterviewerRequest(String text) { this(text, null, null); }
}
