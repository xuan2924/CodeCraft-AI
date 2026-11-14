package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotMessageResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotMessagesResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CopilotTaskService {
    private final CopilotTaskStore store;
    private final StorageCapacityService capacity;
    private final ObjectMapper json;

    public CopilotTaskService(CopilotTaskStore store, StorageCapacityService capacity, ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.json = json;
    }

    public CopilotTaskResponse start(String session, String exercise, String credential, String key, CopilotStartRequest input) {
        if (input == null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return capacity.admit(session, exercise, measurement -> store.accept(session, exercise, credential, key, input.text(), input.feeAcknowledged(), measurement));
    }
    public CopilotDeliveryDTO latest(String session, String exercise) {
        var task = store.latest(session, exercise);
        return task == null ? null : store.delivery(session, task.id());
    }
    public CopilotTaskResponse find(String session, String task) { return store.find(session, task); }
    public CopilotTaskResponse stop(String session, String task, String key) { return store.stop(session, task, key); }

    public CopilotMessagesResponse messages(String session, String exercise, long after) {
        var records = store.messages(session, exercise, after);
        var messages = records.stream().map(value -> {
            var message = ChatMessageDeserializer.messageFromJson(value.payload());
            String text;
            if (message instanceof UserMessage user) text = user.singleText();
            else if (message instanceof AiMessage assistant) {
                text = (assistant.text() == null ? "" : assistant.text()) + (assistant.hasToolExecutionRequests() ? "\n工具调用：" + assistant.toolExecutionRequests().stream().map(call -> call.name() + " [" + call.id() + "]").toList() : "");
            } else if (message instanceof ToolExecutionResultMessage result) text = result.toolName() + " [" + result.id() + "]\n" + result.text();
            else throw new IllegalStateException("Unsupported stored message");
            return new CopilotMessageResponse(Long.toString(value.sequence()), value.id(), value.taskId(), value.kind(), text);
        }).toList();
        return new CopilotMessagesResponse(messages, messages.isEmpty() ? null : messages.getLast().sequence());
    }

    public TaskStreamBatchDTO batch(String session, String exercise, String task, String after, boolean deliver) {
        var snapshot = store.delivery(session, task);
        if (!snapshot.task().exerciseId().equals(exercise)) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        if (after != null && (after.length() > 512 || after.contains("\n") || after.contains("\r"))) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String cursor = task + ":" + snapshot.task().state() + ":" + snapshot.lastSequence() + ":" + snapshot.task().reason() + ":" + snapshot.textHistory() + ":" + snapshot.partial().turnId() + ":" + snapshot.partial().sequence();
        if (!deliver || cursor.equals(after)) return new TaskStreamBatchDTO(snapshot.userId(), after, new byte[0]);
        try {
            String payload = json.writeValueAsString(Map.of("task", snapshot.task(), "lastSequence", snapshot.lastSequence(), "textHistory", snapshot.textHistory(), "partial", snapshot.partial()));
            return new TaskStreamBatchDTO(snapshot.userId(), cursor, ("id:" + cursor + "\nevent:copilot_snapshot\ndata:" + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize Copilot snapshot", failure); }
    }
}
