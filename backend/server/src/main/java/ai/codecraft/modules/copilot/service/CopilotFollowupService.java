package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResumeResponse;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotFollowupRepository;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CopilotFollowupService {
    private final CopilotFollowupStore store;
    private final StorageCapacityService capacity;
    private final CopilotFollowupRepository queue;
    private final Clock clock;
    private final ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore workspace;
    private long cursor;
    public CopilotFollowupService(CopilotFollowupStore store, StorageCapacityService capacity, CopilotFollowupRepository queue, Clock clock, ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore workspace) {
        this.workspace = workspace;
        this.store = store; this.capacity = capacity; this.queue = queue; this.clock = clock;
    }
    public CopilotFollowupResponse enqueue(String session, String exercise, String conversation, String credential, String key, CopilotFollowupRequest input) {
        var receipt = store.previous(session, exercise, conversation, key, input);
        return receipt != null ? receipt : capacity.admit(session, exercise, measurement -> store.accept(session, exercise, conversation, credential, key, input, measurement));
    }
    public CopilotFollowupResumeResponse resume(String session, String exercise, String conversation, String credential, String key, boolean fee) {
        var receipt = store.previousResume(session, exercise, conversation, key, fee);
        return receipt != null ? receipt : capacity.admit(session, exercise, measurement -> store.resume(session, exercise, conversation, credential, key, fee, measurement));
    }
    public List<CopilotFollowupResponse> recent(String session, String exercise, String conversation) { return store.recent(session, exercise, conversation); }
    public CopilotFollowupResponse cancel(String session, String exercise, String conversation, String id) { return store.cancel(session, exercise, conversation, id); }
    public synchronized List<String> pending() {
        var probes = queue.probes(cursor);
        if (probes.isEmpty()) { cursor = 0; return List.of(); }
        var phases = workspace.phases(probes.stream().map(row -> row.exercise()).distinct().toList()).stream()
            .collect(java.util.stream.Collectors.toMap(phase -> phase.id(), phase -> phase));
        var ready = new java.util.ArrayList<String>();
        var now = clock.instant();
        for (var probe : probes) {
            cursor = probe.sequence();
            var phase = phases.get(probe.exercise());
            if (phase == null || phase.ended(now) || ("ACTIVE".equals(phase.state()) ? probe.dispatchable() : "WAITING".equals(probe.state()))) ready.add(probe.id());
            if (ready.size() == 32) return ready;
        }
        if (probes.size() < 128) cursor = 0;
        return ready;
    }
    public void dispatch(String id) {
        var ready = store.ready(id);
        if (ready == null) return;
        try { capacity.admit(ready.sessionHash(), ready.exerciseId(), measurement -> { store.dispatch(id, measurement); return null; }); }
        catch (BusinessException blocked) {
            if (!CopilotErrorCode.COPILOT_BUSY.code().equals(blocked.code())) store.pause(id, ready.generation(), blocked.code());
        }
    }
}
