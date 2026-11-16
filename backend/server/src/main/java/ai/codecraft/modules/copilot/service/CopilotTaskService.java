package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotMessagesResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CopilotTaskService {
    private final CopilotTaskStore store;
    private final StorageCapacityService capacity;
    private final ObjectMapper json;
    private final CopilotReferences references;

    public CopilotTaskService(CopilotTaskStore store, StorageCapacityService capacity, ObjectMapper json, CopilotReferences references) {
        this.store = store; this.capacity = capacity; this.json = json; this.references = references;
    }

    public CopilotTaskResponse start(String session, String exercise, String credential, String key, CopilotStartRequest input) {
        if (input == null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var previous = store.previousStart(session, exercise, key, input);
        if (previous != null) return previous;
        var prepared = references.prepare(session, exercise, input.references());
        return capacity.admit(session, exercise, measurement -> store.acceptPrepared(session, exercise, credential, key, input, prepared, measurement));
    }
    public CopilotDeliveryDTO latestInConversation(String session, String exercise, String conversation) {
        var task = store.latestInConversation(session, exercise, conversation);
        return task == null ? null : store.delivery(session, task.id());
    }
    public CopilotMessagesResponse messagesInConversation(String session, String exercise, long after, String conversation) {
        var messages = store.messagesInConversation(session, exercise, after, conversation).stream().map(CopilotMessagePresentation::from).toList();
        return new CopilotMessagesResponse(messages, messages.isEmpty() ? null : messages.getLast().sequence());
    }
    public CopilotTaskResponse find(String session, String task) { return store.find(session, task); }
    public CopilotTaskResponse stop(String session, String task, String key) { return store.stop(session, task, key); }

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
