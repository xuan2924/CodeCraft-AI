package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotForkPreparedDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.springframework.stereotype.Component;

/** Validates retained native protocol outside a transaction; never executes inherited tools. */
@Component
public class CopilotForkBoundary {
    public void validate(CopilotForkPreparedDTO prepared) {
        var pending = new ArrayDeque<ToolExecutionRequest>();
        long sequence = 0;
        for (var row : prepared.messages()) {
            if (row.sequence() <= sequence) invalid();
            sequence = row.sequence();
            var message = ChatMessageDeserializer.messageFromJson(row.payload());
            if (message instanceof AiMessage assistant) {
                if (!pending.isEmpty()) invalid();
                var unique = new HashSet<String>();
                for (var call : assistant.toolExecutionRequests()) {
                    if (call.id() == null || call.name() == null || !unique.add(call.id())) invalid();
                    pending.add(call);
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                if (pending.isEmpty()) invalid();
                var call = pending.removeFirst();
                if (!call.id().equals(result.id()) || !call.name().equals(result.toolName())) invalid();
            } else if (!(message instanceof UserMessage) || !pending.isEmpty()) invalid();
        }
        if (!pending.isEmpty() || sequence != prepared.throughSequence()) invalid();
    }
    private void invalid() { throw new BusinessException(CopilotErrorCode.COPILOT_FORK_BOUNDARY); }
}
