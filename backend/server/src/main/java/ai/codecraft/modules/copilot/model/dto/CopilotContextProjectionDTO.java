package ai.codecraft.modules.copilot.model.dto;

import dev.langchain4j.model.chat.request.ChatRequest;

/** Internal conservative estimate, never supplier-measured token usage. */
public record CopilotContextProjectionDTO(ChatRequest request, long estimatedTokens, long encodedBytes, long microSavedBytes) {
    @Override public String toString() { return "CopilotContextProjectionDTO[REDACTED]"; }
}
