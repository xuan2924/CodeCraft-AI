package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import dev.langchain4j.data.message.ChatMessage;

public record CopilotContextItemDTO(CopilotMessageEntity source, ChatMessage message, String evidence) {
    @Override public String toString() { return "CopilotContextItemDTO[REDACTED]"; }
}
