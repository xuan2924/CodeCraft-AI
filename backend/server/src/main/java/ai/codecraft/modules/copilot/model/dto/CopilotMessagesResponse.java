package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotMessagesResponse(List<CopilotMessageResponse> messages, String nextCursor) {
    public CopilotMessagesResponse { messages = List.copyOf(messages); }
}
