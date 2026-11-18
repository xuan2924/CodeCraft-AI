package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import ai.codecraft.modules.workspace.model.dto.ExercisePhaseDTO;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short storage transactions only; intentionally independent of identity services to avoid a fencing cycle. */
@Service
public class WorkspaceCoordinationStore {
    private final WorkspaceWriteRepository writes;
    private final ExerciseRepository exercises;
    private final ObjectMapper json;
    private final WorkspaceHistoryLedger history;

    public WorkspaceCoordinationStore(WorkspaceWriteRepository writes, ExerciseRepository exercises, ObjectMapper json, WorkspaceHistoryLedger history) {
        this.writes = writes;
        this.exercises = exercises;
        this.json = json;
        this.history = history;
    }

    @Transactional
    public List<String> captureFences(String user) {
        List<String> ids = new ArrayList<>();
        for (String exercise : writes.controlled(user)) {
            WorkspaceControlEntity control = writes.lock(exercise);
            if (control.gateState().equals("FROZEN")) continue;
            String id = control.fenceId();
            if (id == null) {
                id = UUID.randomUUID().toString();
                writes.insertTransition(new WorkspaceTransitionEntity(id, exercise, "REVOKE", null, "CLOSING", null));
                writes.fence(exercise, id);
            }
            ids.add(id);
        }
        return ids;
    }

    @Transactional
    public void checkControl(String exercise, String hash) {
        WorkspaceControlEntity control = writes.lock(exercise);
        if (control == null || hash == null || !hash.equals(control.credentialHash())) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
        if (control.transitionId() != null || control.fenceId() != null) throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
    }

    @Transactional
    public void fenceForAbandon(String exercise, String id) {
        WorkspaceControlEntity control = writes.lock(exercise);
        if (control == null) return;
        writes.insertTransition(new WorkspaceTransitionEntity(id, exercise, "REVOKE", null, "CLOSING", null));
        writes.fence(exercise, id);
    }

    @Transactional(readOnly = true)
    public WorkspaceTransitionEntity transition(String id) { return writes.transition(id); }
    @Transactional(readOnly = true)
    public WorkspaceControlEntity control(String id) { return writes.control(id); }
    @Transactional(readOnly = true)
    public List<ExercisePhaseDTO> phases(List<String> ids) { return exercises.phases(ids); }
    @Transactional(readOnly = true)
    public ExerciseEntity exercise(String id) { return exercises.find(id); }
    @Transactional(readOnly = true)
    public WorkspaceMutationEntity mutation(String id) { return writes.mutation(id); }
    @Transactional(readOnly = true)
    public List<WorkspaceOperationEntity> unresolved(String id) { return writes.unresolved(id); }
    @Transactional(readOnly = true)
    public List<WorkspaceTransitionEntity> pendingTransitions() { return writes.pendingTransitions(); }
    @Transactional(readOnly = true)
    public List<WorkspaceOperationEntity> pendingMutations() { return writes.pendingMutations(); }
    @Transactional
    public void attempted(String id) { writes.attempted(id); exercises.attempted(id); }

    @Transactional
    public boolean activateFence(String id) {
        WorkspaceTransitionEntity transition = writes.transition(id);
        WorkspaceControlEntity control = writes.lock(transition.exerciseId());
        if (id.equals(control.transitionId())) return true;
        if (control.transitionId() != null || !id.equals(control.fenceId())) return false;
        writes.activate(control.exerciseId(), id);
        return true;
    }

    @Transactional
    public void expectation(String id, long epoch) {
        WorkspaceTransitionEntity transition = writes.transition(id);
        WorkspaceControlEntity control = writes.lock(transition.exerciseId());
        if (id.equals(control.transitionId()) && transition.expectedEpoch() == null && epoch >= 0 && epoch < Long.MAX_VALUE - 2) {
            writes.expectation(id, epoch);
        }
    }

    @Transactional
    public void closed(String id) {
        WorkspaceTransitionEntity transition = writes.transition(id);
        WorkspaceControlEntity control = writes.lock(transition.exerciseId());
        if (!id.equals(control.transitionId()) || !transition.phase().equals("CLOSING")) return;
        if (!writes.unresolved(control.exerciseId()).isEmpty()) throw new IllegalStateException("Unresolved writes remain behind fence");
        writes.gate(control.exerciseId(), transition.expectedEpoch() + 1, "CLOSED");
        if (transition.kind().equals("END")) {
            writes.phase(id, "SUCCEEDED");
            writes.activate(control.exerciseId(), null);
            writes.holder(control.exerciseId(), null);
        } else if (transition.kind().equals("REVOKE")) {
            writes.phase(id, "SUCCEEDED");
            writes.activate(control.exerciseId(), null);
            writes.fence(control.exerciseId(), null);
        } else if (transition.kind().equals("PAUSE") || (!transition.kind().equals("RESUME")
            && List.of("PAUSING", "PAUSED", "RESUMING").contains(exercises.find(control.exerciseId()).state()))) {
            // Reconciliation and ownership changes must not undo a lifecycle pause.
            writes.phase(id, "SUCCEEDED");
            writes.activate(control.exerciseId(), null);
            if (List.of("ACQUIRE", "TAKEOVER", "RELEASE").contains(transition.kind())) writes.holder(control.exerciseId(), transition.targetHash());
            if (!transition.kind().equals("PAUSE")) exercises.operationState(id, "SUCCEEDED");
        } else writes.phase(id, "OPENING");
    }

    @Transactional
    public void opened(String id) {
        WorkspaceTransitionEntity transition = writes.transition(id);
        WorkspaceControlEntity control = writes.lock(transition.exerciseId());
        if (!id.equals(control.transitionId()) || !transition.phase().equals("OPENING")) return;
        writes.gate(control.exerciseId(), transition.expectedEpoch() + 2, "OPEN");
        if (List.of("ACQUIRE", "TAKEOVER", "RELEASE").contains(transition.kind())) writes.holder(control.exerciseId(), transition.targetHash());
        writes.phase(id, "SUCCEEDED");
        writes.activate(control.exerciseId(), null);
        // Resume is complete only when its new authoritative deadline is committed.
        if (!transition.kind().equals("RESUME")) exercises.operationState(id, "SUCCEEDED");
    }

    @Transactional
    public String reconcile(String exercise) {
        WorkspaceControlEntity control = writes.lock(exercise);
        if (control.fenceId() != null) return control.fenceId();
        if (control.transitionId() != null) return control.transitionId();
        String id = UUID.randomUUID().toString();
        writes.insertTransition(new WorkspaceTransitionEntity(id, exercise, "RECONCILE", null, "CLOSING", null));
        writes.activate(exercise, id);
        return id;
    }

    @Transactional
    public WorkspaceMutationEntity claim(WorkspaceOperationEntity operation) {
        WorkspaceControlEntity control = writes.lock(operation.exerciseId());
        exercises.attempted(operation.id());
        WorkspaceOperationEntity current = exercises.operation(operation.id());
        if (!current.state().equals("PENDING") || control.transitionId() != null || control.fenceId() != null) return null;
        return writes.dispatch(operation.id()) == 1 ? writes.mutation(operation.id()) : null;
    }

    @Transactional
    public void unknown(String id) { writes.unknown(id); }

    @Transactional
    public void result(WorkspaceOperationEntity operation, MutationReceipt receipt, WorkspaceSnapshot snapshot, String failure) {
        writes.lock(operation.exerciseId());
        WorkspaceOperationEntity current = exercises.operation(operation.id());
        if (List.of("SUCCEEDED", "REJECTED").contains(current.state())) return;
        if (receipt == null) {
            writes.result(operation.id(), null, null, failure);
            exercises.operationState(operation.id(), "REJECTED");
        } else {
            // A no-op must not expose another writer's newer Go snapshot before
            // that writer's history references have committed in Java.
            if (receipt.getChanged()) {
                history.mutation(current, receipt.getRevision());
                try {
                    writes.observed(operation.exerciseId(), snapshot.getRevision(), json.writeValueAsString(snapshot.getPathsList()));
                } catch (JsonProcessingException error) { throw new IllegalStateException("Invalid workspace paths", error); }
            }
            writes.result(operation.id(), receipt.getRevision(), receipt.getChanged(), null);
            exercises.operationState(operation.id(), "SUCCEEDED");
        }
    }
}
