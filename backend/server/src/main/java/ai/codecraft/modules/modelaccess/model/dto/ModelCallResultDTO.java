package ai.codecraft.modules.modelaccess.model.dto;

import dev.langchain4j.model.chat.response.ChatResponse;

public record ModelCallResultDTO(ChatResponse response, int decodedBytes) {
    @Override public String toString() { return "ModelCallResultDTO[REDACTED]"; }
}
