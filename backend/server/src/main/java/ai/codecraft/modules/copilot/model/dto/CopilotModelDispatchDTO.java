package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotTurnEntity;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import dev.langchain4j.model.chat.request.ChatRequest;

public record CopilotModelDispatchDTO(CopilotTurnEntity turn, ModelInvocationDTO target, ChatRequest request, boolean textHistory) {
    @Override public String toString() { return "CopilotModelDispatchDTO[turn=" + turn.id() + ", payload=redacted]"; }
}
