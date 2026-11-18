package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.SandboxNodeDTO;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.SandboxNodeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SandboxRoutingStore {
    private final SandboxNodeRepository repository;
    private final Clock clock;
    public SandboxRoutingStore(SandboxNodeRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(String exercise) {
        // Lock all candidate rows in deterministic order before computing reservations; serializes Java node choices.
        repository.candidates();
        // A fresh READ COMMITTED statement after waiting for locks sees the preceding allocator's binding.
        var nodes = repository.candidates();
        String existing = repository.bound(exercise);
        if (existing != null) return;
        repository.bind(exercise, select(nodes, clock.instant()).id());
    }
    public static SandboxNodeDTO select(List<SandboxNodeDTO> nodes, Instant now) {
        return nodes.stream().filter(node -> node.healthy(now) && node.ready() && node.free() > 0)
            .sorted(Comparator.comparingInt(SandboxNodeDTO::free).reversed().thenComparing(SandboxNodeDTO::id)).findFirst()
            .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE));
    }
    /** Called while the existing global execution-admission row lock is held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admitExecution(String exercise) {
        var node = forExercise(exercise);
        if (!node.ready() || node.free() == 0 || repository.occupied(node.id()) >= node.maxSlots()) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        }
    }
    public void requireExecutionReady(String instance) {
        var node = forInstance(instance);
        if (!node.ready() || node.free() == 0) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
    }
    public SandboxNodeDTO forInstance(String instance) { return require(repository.instance(instance)); }
    public SandboxNodeDTO forExercise(String exercise) { return require(repository.bound(exercise)); }
    public SandboxNodeDTO require(String id) {
        var node = id == null ? null : repository.node(id);
        if (node == null || !node.healthy(clock.instant())) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        return node;
    }
}
