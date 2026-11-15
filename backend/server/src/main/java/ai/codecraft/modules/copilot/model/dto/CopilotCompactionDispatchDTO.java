package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotCompactionCallEntity;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import dev.langchain4j.model.chat.request.ChatRequest;

public record CopilotCompactionDispatchDTO(CopilotCompactionCallEntity call, ModelInvocationDTO target, ChatRequest request) {
    @Override public String toString() { return "CopilotCompactionDispatchDTO[REDACTED]"; }
}
