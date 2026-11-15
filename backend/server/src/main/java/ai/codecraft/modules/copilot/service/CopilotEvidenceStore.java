package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotEvidenceDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotDelegationRepository;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotSourceDTO;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotEvidenceStore {
    private final CopilotTaskRepository tasks;
    private final CopilotCompactionRepository contexts;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final Clock clock;
    private final SkillSnapshots skills;
    private final CopilotDelegationRepository delegations;

    public CopilotEvidenceStore(CopilotTaskRepository tasks, CopilotCompactionRepository contexts,
        IdentityTransactionService identity, WorkspaceCoordinationStore workspace, Clock clock, SkillSnapshots skills, CopilotDelegationRepository delegations) {
        this.tasks = tasks; this.contexts = contexts; this.identity = identity; this.workspace = workspace; this.clock = clock; this.skills = skills; this.delegations = delegations;
    }

    /** Authorization takes an owner row lock; large payload decoding remains outside this transaction. */
    @Transactional
    public CopilotEvidenceDTO read(String taskId, String worker, String id) {
        var task = authorize(taskId, worker);
        var message = tasks.messageById(id);
        var origin = message == null ? null : tasks.find(message.taskId());
        if (message != null && origin != null && task.exerciseId().equals(message.exerciseId()) && (task.conversationId().equals(origin.conversationId()) || tasks.hasMessage(task.conversationId(), id) || delegations.evidenceVisible(task.conversationId(), origin.id()))) {
            return new CopilotEvidenceDTO(id, message.kind(), message.sequence(), message.payload(), null, null);
        }
        var summary = contexts.find(id);
        if (summary != null && task.exerciseId().equals(summary.exerciseId()) && task.conversationId().equals(summary.conversationId()) && "APPLIED".equals(summary.state())) {
            return new CopilotEvidenceDTO(id, "SUMMARY", summary.throughSequence(), summary.summaryPayload(), summary.sourceIdsPayload(), summary.sourceSummaryId());
        }
        throw new BusinessException(CopilotErrorCode.COPILOT_EVIDENCE_NOT_FOUND);
    }

    @Transactional
    public SkillSnapshotSourceDTO readSkill(String taskId, String worker, String skill) {
        var task = authorize(taskId, worker);
        return skills.source(task.userId(), task.id(), skill);
    }

    private CopilotTaskEntity authorize(String taskId, String worker) {
        var initial = tasks.find(taskId);
        if (initial == null) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(taskId);
        if (task == null) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        var grant = identity.authorizePrivateDelivery(task.sessionHash());
        if (!"USER".equals(grant.role()) || !grant.userId().equals(task.userId()) || worker == null || !worker.equals(task.workerId())
            || task.workerNotAfter() == null || !clock.instant().isBefore(task.workerNotAfter()) || task.stopRequested() || !"RUNNING".equals(task.state())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        var exercise = workspace.exercise(task.exerciseId());
        if (exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        return task;
    }
}
