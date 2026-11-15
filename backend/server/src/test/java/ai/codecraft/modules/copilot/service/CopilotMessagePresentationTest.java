package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopilotMessagePresentationTest {
    @Test
    void presentsPlainThinkingSeparatelyWithoutExposingOpaqueProtocolFields() {
        var message = AiMessage.builder().text("```java\nreturn 42;\n```").thinking("Check the return value")
            .attributes(Map.of("encrypted_reasoning", "not-for-display", "thinking_signature", "signature-only")).build();
        var stored = new CopilotMessageEntity(1L, "message", "task", "exercise", "ASSISTANT",
            ChatMessageSerializer.messageToJson(message), Instant.EPOCH);
        var shown = CopilotMessagePresentation.from(stored);
        assertThat(shown.text()).isEqualTo("```java\nreturn 42;\n```");
        assertThat(shown.thinking()).isEqualTo("Check the return value");
        assertThat(shown.toString()).doesNotContain("not-for-display", "signature-only");
        assertThat(stored.payload()).contains("not-for-display", "signature-only");
    }
}
