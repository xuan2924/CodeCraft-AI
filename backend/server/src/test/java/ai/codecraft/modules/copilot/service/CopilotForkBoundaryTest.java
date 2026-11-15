package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotForkPreparedDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopilotForkBoundaryTest {
    private final CopilotForkBoundary boundary = new CopilotForkBoundary();
    private CopilotMessageEntity row(int sequence, ChatMessage message) {
        return new CopilotMessageEntity(sequence, "message-" + sequence, "task", "exercise", "EVIDENCE", ChatMessageSerializer.messageToJson(message), Instant.EPOCH);
    }
    @Test void requiresCompleteNativeToolPairsAndDoesNotTransformProtocol() {
        var call = ToolExecutionRequest.builder().id("call").name("bash").arguments("{}").build();
        var user = row(1, UserMessage.from("goal"));
        var assistant = row(2, AiMessage.builder().toolExecutionRequests(List.of(call)).thinking("native thinking").attributes(Map.of("signature", "retained")).build());
        var result = row(3, ToolExecutionResultMessage.from(call, "original result"));
        String original = assistant.payload();
        boundary.validate(new CopilotForkPreparedDTO("source", "message-1", 1, List.of(user)));
        boundary.validate(new CopilotForkPreparedDTO("source", "message-3", 3, List.of(user, assistant, result)));
        assertThat(assistant.payload()).isEqualTo(original).contains("native thinking", "retained");
        for (var rows : List.of(List.of(user, assistant), List.of(result), List.of(user, assistant, row(3, UserMessage.from("interrupt"))),
            List.of(user, assistant, row(3, ToolExecutionResultMessage.from("other", "bash", "wrong"))))) {
            assertThatThrownBy(() -> boundary.validate(new CopilotForkPreparedDTO("source", "message", rows.getLast().sequence(), rows)))
                .isInstanceOf(BusinessException.class).hasMessage("COPILOT_FORK_BOUNDARY");
        }
    }
}
