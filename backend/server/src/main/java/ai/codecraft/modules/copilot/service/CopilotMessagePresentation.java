package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotMessageResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/** Display projection only: original protocol messages remain unchanged for subsequent requests. */
public final class CopilotMessagePresentation {
    private CopilotMessagePresentation() {}

    public static CopilotMessageResponse from(CopilotMessageEntity value) {
        var message = ChatMessageDeserializer.messageFromJson(value.payload());
        String text;
        String thinking = "";
        if (message instanceof UserMessage user) text = user.singleText();
        else if (message instanceof AiMessage assistant) {
            text = (assistant.text() == null ? "" : assistant.text()) + (assistant.hasToolExecutionRequests()
                ? "\n工具调用：" + assistant.toolExecutionRequests().stream().map(call -> call.name() + " [" + call.id() + "]").toList() : "");
            thinking = assistant.thinking() == null ? "" : assistant.thinking();
        } else if (message instanceof ToolExecutionResultMessage result) text = result.toolName() + " [" + result.id() + "]\n" + result.text();
        else throw new IllegalStateException("Unsupported stored message");
        return new CopilotMessageResponse(Long.toString(value.sequence()), value.id(), value.taskId(), value.kind(), text, thinking);
    }
}
