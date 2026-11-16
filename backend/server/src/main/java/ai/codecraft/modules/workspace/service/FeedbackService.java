package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.FeedbackPreviewDTO;
import ai.codecraft.modules.workspace.model.dto.FeedbackRequest;
import ai.codecraft.modules.workspace.model.dto.FeedbackResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private final FeedbackStore store;
    private final StorageCapacityService capacity;
    public FeedbackService(FeedbackStore store, StorageCapacityService capacity) { this.store = store; this.capacity = capacity; }
    public FeedbackPreviewDTO preview(String session, String exercise, FeedbackRequest input) { return store.preview(session, exercise, input); }
    public FeedbackResponse submit(String session, String exercise, String key, FeedbackRequest input) {
        var previous = store.existing(session, exercise, key, input);
        return previous != null ? previous : capacity.admit(session, exercise, measurement -> store.submit(session, exercise, key, input, measurement));
    }
    public List<FeedbackResponse> list(String session, boolean admin, String after) { return store.list(session, admin, after); }
    public FeedbackResponse reply(String session, String id, String key, String reply) { return store.reply(session, id, key, reply); }
}
