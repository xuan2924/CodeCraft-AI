package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.InterviewerTaskResponse;
import ai.codecraft.modules.workspace.model.dto.InterviewerRequest;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InterviewerService {
    private final InterviewerStore store;
    private final StorageCapacityService capacity;
    private final IdentityTransactionService identity;
    private final ObjectMapper json;
    public InterviewerService(InterviewerStore store, StorageCapacityService capacity, IdentityTransactionService identity, ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.identity = identity; this.json = json;
    }
    public InterviewerTaskResponse ask(String session, String exercise, String key, String credential, String text) {
        return ask(session, exercise, key, credential, new InterviewerRequest(text));
    }
    public InterviewerTaskResponse ask(String session, String exercise, String key, String credential, InterviewerRequest input) {
        var previous = store.existing(session, exercise, key, input);
        return previous != null ? previous : capacity.admit(session, exercise, measurement -> store.accept(session, exercise, key, credential, input, measurement));
    }
    public List<InterviewerTaskResponse> history(String session, String exercise, String before) { return store.history(session, exercise, before); }
    public InterviewerTaskResponse find(String session, String exercise, String id) { return store.find(session, exercise, id); }
    public TaskStreamBatchDTO batch(String session, String exercise, String id, String after, boolean deliver) {
        var snapshot = store.find(session, exercise, id);
        String user = identity.authorization(session).userId();
        String cursor = id + ":" + snapshot.state();
        if (!deliver || cursor.equals(after)) return new TaskStreamBatchDTO(user, after, new byte[0]);
        try {
            String payload = json.writeValueAsString(snapshot);
            return new TaskStreamBatchDTO(user, cursor, ("id:" + cursor + "\nevent:interviewer_snapshot\ndata:" + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException error) { throw new IllegalStateException("Invalid interviewer snapshot", error); }
    }
}
