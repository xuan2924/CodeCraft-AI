package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.util.List;

public record CopilotForkPreparedDTO(String sourceConversationId, String throughMessageId, long throughSequence,
    List<CopilotMessageEntity> messages) {
    public CopilotForkPreparedDTO { messages = List.copyOf(messages); }
}
