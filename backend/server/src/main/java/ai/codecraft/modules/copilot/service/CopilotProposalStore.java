package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotFileDiffDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotPendingProposalDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDetailResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalsResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotProposalEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import ai.codecraft.modules.workspace.service.WorkspaceWriteTransactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approval and the existing file-write intent commit together; no RPC occurs in these transactions. */
@Service
public class CopilotProposalStore {
    private final CopilotProposalRepository proposals;
    private final CopilotTaskRepository tasks;
    private final CopilotTaskStore views;
    private final CopilotExecutionStore execution;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceAiOperationService operations;
    private final WorkspaceBatchCodec codec;
    private final ObjectMapper json;
    private final Clock clock;

    public CopilotProposalStore(CopilotProposalRepository proposals, CopilotTaskRepository tasks, CopilotTaskStore views,
        CopilotExecutionStore execution, IdentityTransactionService identity, IdentitySecrets secrets, ExerciseTransactionService exercises,
        WorkspaceCoordinationStore workspace, WorkspaceWriteTransactionService writes, WorkspaceAiOperationService operations,
        WorkspaceBatchCodec codec, @Qualifier("workspaceJson") ObjectMapper json, Clock clock) {
        this.proposals = proposals; this.tasks = tasks; this.views = views; this.execution = execution; this.identity = identity;
        this.secrets = secrets; this.exercises = exercises; this.workspace = workspace; this.writes = writes;
        this.operations = operations; this.codec = codec; this.json = json; this.clock = clock;
    }

    @Transactional
    public boolean register(String taskId, String worker, String toolId, CopilotProposalPreparedDTO prepared) {
        var task = execution.current(taskId, worker);
        if (task == null || task.stopRequested()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        if (!"USER".equals(identity.authorizeMutation(task.sessionHash()).role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        var exercise = exercises.find(task.sessionHash(), task.exerciseId());
        if (ended(exercise)) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!prepared.workspaceInstanceId().equals(exercise.workspaceInstanceId()) || exercise.revision() == null
            || !prepared.revision().equals(exercise.revision().toString())) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        var tool = tasks.toolById(toolId);
        if (tool == null || !taskId.equals(tool.taskId()) || !"propose_changes".equals(tool.name())) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        String fingerprint = secrets.fingerprint(prepared.workspaceInstanceId() + "\n" + prepared.batchPayload());
        if (proposals.rejected(taskId, fingerprint)) return false;
        String id = UUID.randomUUID().toString();
        proposals.insert(new CopilotProposalEntity(id, 0, taskId, toolId, tool.turnId(), task.exerciseId(), task.userId(),
            prepared.workspaceInstanceId(), Long.parseLong(prepared.revision()), "PENDING", "ASK", prepared.files().size(), prepared.batchPayload(),
            encode(prepared.files()), fingerprint, null, null, null, null, null, null, null, null, null, clock.instant(), null, null));
        execution.parkProposal(taskId, worker, toolId);
        return true;
    }

    @Transactional
    public CopilotProposalsResponse list(String session, String taskId, long before) {
        views.find(session, taskId);
        if (before < 0) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var page = proposals.page(taskId, before);
        var items = page.size() > 20 ? page.subList(0, 20) : page;
        return new CopilotProposalsResponse(items, page.size() > 20 ? items.getLast().sequence() : null);
    }

    @Transactional
    public CopilotProposalDetailResponse detail(String session, String id) {
        owned(session, id);
        var proposal = proposals.find(id);
        try {
            List<CopilotFileDiffDTO> files = json.readValue(proposal.diffPayload(), new TypeReference<>() {});
            return new CopilotProposalDetailResponse(proposals.summary(id), files);
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored proposal evidence cannot be decoded"); }
    }

    @Transactional
    public CopilotProposalResponse summary(String session, String id) { owned(session, id); return proposals.summary(id); }

    @Transactional
    public String exercise(String session, String id) { return owned(session, id).exerciseId(); }

    @Transactional
    public CopilotProposalResponse replay(String session, String id, String key, CopilotProposalDecisionRequest input) {
        owned(session, id);
        validate(key, input);
        return replay(proposals.find(id), key, fingerprint(input));
    }

    @Transactional
    public CopilotProposalResponse decide(String session, String id, String credential, String key,
        CopilotProposalDecisionRequest input, StorageMeasurement measurement) {
        var grant = identity.authorizeMutation(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        owned(session, id);
        validate(key, input);
        var proposal = proposals.find(id);
        String fingerprint = fingerprint(input);
        var previous = replay(proposal, key, fingerprint);
        if (previous != null) return previous;
        workspace.checkControl(proposal.exerciseId(), IdentitySecrets.digest(credential));
        var task = tasks.find(proposal.taskId());
        var exercise = exercises.find(session, proposal.exerciseId());
        if (proposals.decision(id, key, fingerprint, input.decision(), grant.userId(), reason(input), clock.instant()) != 1) {
            throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_DECIDED);
        }
        var pending = proposals.identity(id);
        if (task.stopRequested() || ended(exercise)) {
            resolve(pending, "CANCELLED", task.stopRequested() ? "TASK_STOPPED" : "EXERCISE_ENDED", null, null, "");
        } else if (!"WAITING_APPROVAL".equals(task.state()) || task.workerId() != null) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        } else if (stale(pending, exercise)) {
            resolve(pending, "INVALIDATED", "REVISION_CONFLICT", null, null, "");
        } else if ("REJECT".equals(input.decision())) {
            reauthorize(task.id(), session);
            resolve(pending, "REJECTED", "PROPOSAL_REJECTED", null, null, reason(input));
        } else {
            reauthorize(task.id(), session);
            FileBatchRequest batch;
            try { batch = json.readValue(proposal.batchPayload(), FileBatchRequest.class); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored proposal batch cannot be decoded"); }
            // Current path/text policy is checked again; the approved bytes remain immutable.
            String payload = codec.encode(batch);
            if (!payload.equals(proposal.batchPayload())) throw new IllegalStateException("Proposal payload is not canonical");
            var operation = writes.batch(session, proposal.exerciseId(), "copilot:" + proposal.id(), credential, payload, proposal.baseRevision(), measurement);
            if (proposals.applyingOperation(id, operation.id()) != 1) throw new IllegalStateException("Proposal approval was not recorded");
            execution.awaitWrite(task.id(), proposal.toolId());
        }
        return proposals.summary(id);
    }

    /** Reads the authoritative operation receipt; never dispatches or repeats ApplyBatch. */
    @Transactional
    public void recoverTask(String taskId) {
        var task = tasks.find(taskId);
        if (task == null) return;
        identity.lockResourceOwner(task.userId());
        task = tasks.find(taskId);
        var pending = proposals.pending(taskId);
        if (pending == null) return;
        var exercise = workspace.exercise(pending.exerciseId());
        if ("APPLYING".equals(pending.state())) {
            var outcome = operations.mutationOutcome(pending.exerciseId(), pending.operationId());
            if ("SUCCEEDED".equals(outcome.state())) {
                if (outcome.revision() == null || outcome.changed() == null) throw new IllegalStateException("Confirmed mutation lacks its receipt");
                resolve(pending, "APPLIED", "APPLIED", Long.parseLong(outcome.revision()), outcome.changed(), "");
            } else if ("REJECTED".equals(outcome.state())) {
                String code = outcome.failureCode() == null ? "WRITE_NOT_COMMITTED" : outcome.failureCode();
                resolve(pending, "REVISION_CONFLICT".equals(code) ? "INVALIDATED" : "FAILED", code, null, null, "");
            } else if (task.stopRequested() || ended(exercise)) workspace.reconcile(pending.exerciseId());
        } else if (task.stopRequested() || ended(exercise)) {
            resolve(pending, "CANCELLED", task.stopRequested() ? "TASK_STOPPED" : "EXERCISE_ENDED", null, null, "");
        } else if (stale(pending, exercise)) resolve(pending, "INVALIDATED", "REVISION_CONFLICT", null, null, "");
    }

    private void reauthorize(String task, String session) {
        // A fresh explicit decision authorizes continuation; observing/replaying a receipt never does.
        if (tasks.reauthorizeWaiting(task, session) != 1) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
    }

    private void resolve(CopilotPendingProposalDTO proposal, String state, String code, Long revision, Boolean changed, String reason) {
        if (proposals.finish(proposal.id(), state, revision, changed, "APPLIED".equals(state) ? null : code, clock.instant()) != 1) return;
        String body = revision == null ? encode(Map.of("code", code, "reason", reason))
            : encode(Map.of("code", code, "revision", revision.toString(), "changed", changed));
        execution.resolveProposal(proposal.taskId(), proposal.toolId(), body);
    }

    private CopilotPendingProposalDTO owned(String session, String id) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        var proposal = proposals.identity(id);
        if (proposal == null || !grant.userId().equals(proposal.userId())) throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_NOT_FOUND);
        exercises.find(session, proposal.exerciseId());
        return proposal;
    }
    private CopilotProposalResponse replay(CopilotProposalEntity proposal, String key, String fingerprint) {
        if (proposal.decisionKey() != null) {
            if (!proposal.decisionKey().equals(key)) throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_DECIDED);
            if (!proposal.decisionFingerprint().equals(fingerprint)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return proposals.summary(proposal.id());
        }
        return "PENDING".equals(proposal.state()) ? null : proposals.summary(proposal.id());
    }
    private boolean ended(ExerciseEntity exercise) {
        return exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt());
    }
    private boolean stale(CopilotPendingProposalDTO proposal, ExerciseEntity exercise) {
        return !proposal.workspaceInstanceId().equals(exercise.workspaceInstanceId()) || exercise.revision() == null || proposal.baseRevision() != exercise.revision();
    }
    private void validate(String key, CopilotProposalDecisionRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null || !("APPROVE".equals(input.decision()) || "REJECT".equals(input.decision()))
            || reason(input).getBytes(StandardCharsets.UTF_8).length > 2048 || "APPROVE".equals(input.decision()) && !reason(input).isEmpty()) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
    }
    private String fingerprint(CopilotProposalDecisionRequest input) { return secrets.fingerprint(input.decision() + "\n" + reason(input)); }
    private String reason(CopilotProposalDecisionRequest input) { return input.reason() == null ? "" : input.reason(); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Proposal evidence cannot be serialized"); }
    }
}
