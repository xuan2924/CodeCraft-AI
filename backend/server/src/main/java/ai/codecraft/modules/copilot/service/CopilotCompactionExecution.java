package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionDispatchDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionPlanDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionReceiptDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextTicketDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotCompactionCallEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotCompactionEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable paid-call boundary. Parsing, SDK calls and summary validation belong outside these transactions. */
@Service
public class CopilotCompactionExecution {
    private static final long CALL_CREDIT = 2 * 1024 * 1024;
    private final CopilotCompactionRepository contexts;
    private final CopilotTaskRepository tasks;
    private final CopilotSteerRepository steers;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final ExerciseModelService models;
    private final CopilotProperties limits;
    private final Clock clock;
    private final CopilotCallEvidence evidence;

    public CopilotCompactionExecution(CopilotCompactionRepository contexts, CopilotTaskRepository tasks, CopilotSteerRepository steers,
        IdentityTransactionService identity, WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations,
        ExerciseModelService models, CopilotProperties limits, Clock clock, CopilotCallEvidence evidence) {
        this.contexts = contexts; this.tasks = tasks; this.steers = steers; this.identity = identity; this.workspace = workspace;
        this.operations = operations; this.models = models; this.limits = limits; this.clock = clock; this.evidence = evidence;
    }

    @Transactional
    public CopilotCompactionEntity claim(String id, String worker, String inlineTask) {
        var job = locked(id);
        if (job == null || !"WAITING".equals(job.state())) return null;
        String cancelled = cancellation(job);
        if (cancelled != null) { finish(job, "CANCELLED", null, cancelled); return null; }
        var active = tasks.activeInConversation(job.conversationId());
        if (inlineTask != null) {
            if (active == null || !inlineTask.equals(active.id()) || !worker.equals(active.workerId()) || active.workerNotAfter() == null
                || !clock.instant().isBefore(active.workerNotAfter()) || !"RUNNING".equals(active.state()) || !settledBatch(active.id())) return null;
        } else if (active != null && (!"PAUSED".equals(active.state()) || !settledBatch(active.id()))) return null;
        if (contexts.claim(id, worker, inlineTask, clock.instant().plus(limits.workerLease()), clock.instant()) != 1) return null;
        return contexts.find(id);
    }

    @Transactional
    public CopilotContextTicketDTO ticket(String id, String worker) {
        var job = owned(id, worker);
        if (job == null) return null;
        String cancelled = cancellation(job);
        if (cancelled != null) { finish(job, "CANCELLED", null, cancelled); return null; }
        var target = models.resolveForCall(job.sessionHash(), job.exerciseId());
        var head = contexts.head(job.conversationId());
        long convertedThrough = contexts.foreignThrough(job.conversationId(), head.throughSequence(), target.connection().id(), target.model().id(),
            Long.parseLong(target.connection().configurationVersion()), Long.parseLong(target.model().configurationVersion()));
        return new CopilotContextTicketDTO(job.anchorTaskId(), job.exerciseId(), job.sessionHash(), worker, head.version(), head.summaryId(),
            head.throughSequence(), tasks.lastSequence(job.conversationId()), target,
            job.boundTaskId() == null ? List.of() : steers.pendingIds(job.boundTaskId()), convertedThrough, job.conversationId());
    }

    @Transactional
    public CopilotCompactionDispatchDTO begin(String id, String worker, CopilotContextPreparedDTO prepared,
        CopilotCompactionPlanDTO candidate, String sourceIdsPayload, StorageMeasurement measurement) {
        var job = owned(id, worker);
        if (job == null) return null;
        String cancelled = cancellation(job);
        if (cancelled != null) { finish(job, "CANCELLED", null, cancelled); return null; }
        var previous = contexts.latestCall(id);
        if (previous != null && "DISPATCHED".equals(previous.state())) throw new IllegalStateException("Summary request is already dispatched");
        if (job.attemptCount() >= 2) throw new IllegalStateException("Summary repair limit reached");
        var ticket = prepared.ticket();
        if (!job.exerciseId().equals(ticket.exerciseId()) || !job.conversationId().equals(ticket.conversationId()) || !worker.equals(ticket.workerId()) || !job.sessionHash().equals(ticket.sessionHash())) {
            throw new IllegalArgumentException("Summary snapshot does not belong to this worker");
        }
        var head = contexts.head(job.conversationId());
        if (head.version() != ticket.version() || tasks.lastSequence(job.conversationId()) != ticket.highWater()) return null;
        var target = models.resolveForCall(job.sessionHash(), job.exerciseId());
        if (!target.connection().equals(ticket.target().connection()) || !target.model().equals(ticket.target().model())) return null;
        if (candidate.throughSequence() < head.throughSequence() || candidate.throughSequence() > ticket.highWater()) {
            throw new IllegalArgumentException("Summary source range is outside its snapshot");
        }
        Instant until = deadline(job);
        var call = new CopilotCompactionCallEntity(UUID.randomUUID().toString(), id, job.exerciseId(), job.attemptCount() + 1, "DISPATCHED",
            target.connection().id(), target.model().id(), Long.parseLong(target.connection().configurationVersion()),
            Long.parseLong(target.model().configurationVersion()), target.connection().protocol().name(), target.model().modelId(),
            target.model().contextWindow(), candidate.outputTokens(), head.version(), head.summaryId(), candidate.throughSequence(),
            sourceIdsPayload, candidate.estimatedTokens(), prepared.configurationPayload(), null, null, null, null, true, null, null,
            clock.instant(), until, null);
        operations.reserve(job.sessionHash(), job.exerciseId(), call.id(), "COPILOT_SUMMARY", measurement, CALL_CREDIT);
        Instant lease = clock.instant().plus(limits.workerLease());
        if (contexts.attempt(id, worker, head.version(), head.summaryId(), candidate.throughSequence(), sourceIdsPayload, lease) != 1) {
            throw new IllegalStateException("Summary worker lost its dispatch slot");
        }
        contexts.call(call);
        contexts.recordPrice(call.id(), evidence.price(target, call.createdAt()));
        if (job.workerTaskId() != null) tasks.renew(job.workerTaskId(), worker, lease);
        return new CopilotCompactionDispatchDTO(call, target, candidate.request());
    }

    @Transactional
    public void partial(String id, String worker, String callId, String payload) {
        var job = owned(id, worker);
        if (job == null) return;
        var call = contexts.latestCall(id);
        if (call != null && callId.equals(call.id()) && "DISPATCHED".equals(call.state())) contexts.partial(callId, payload);
    }

    /** Returns true only when the one bounded format repair is still authorized. */
    @Transactional
    public boolean complete(String id, String worker, String callId, CopilotCompactionReceiptDTO receipt) {
        var job = owned(id, worker);
        if (job == null) return false;
        var call = contexts.latestCall(id);
        if (call == null || !callId.equals(call.id()) || !"DISPATCHED".equals(call.state())) return false;
        boolean valid = receipt.summaryPayload() != null && receipt.failureCode() == null;
        if (contexts.result(callId, valid ? "SUCCEEDED" : "FAILED", receipt, clock.instant()) != 1) return false;
        contexts.accounting(callId, evidence.receipt(receipt.usageFacts(), contexts.priceSnapshot(callId)));
        operations.settle(job.exerciseId(), callId, valid);
        String cancelled = cancellation(job);
        if (cancelled != null) { finish(job, "CANCELLED", null, cancelled); return false; }
        if (valid) {
            if (contexts.publish(job.conversationId(), call.sourceVersion(), id, call.throughSequence(), clock.instant()) != 1) {
                finish(job, "INTERRUPTED", null, "CONTEXT_VERSION_CHANGED"); return false;
            }
            finish(job, "APPLIED", receipt.summaryPayload(), null);
            resumeContextParent(job);
            return false;
        }
        if ("COPILOT_SUMMARY_INVALID".equals(receipt.failureCode()) && job.attemptCount() < 2) return true;
        finish(job, "FAILED", null, receipt.failureCode());
        return false;
    }

    @Transactional
    public void noCandidate(String id, String worker, boolean safe) {
        var job = owned(id, worker);
        if (job == null) return;
        String cancelled = cancellation(job);
        if (cancelled != null) { finish(job, "CANCELLED", null, cancelled); return; }
        finish(job, safe ? "NOT_NEEDED" : "FAILED", null, safe ? null : "COPILOT_CONTEXT_REQUIRED");
        if (safe) resumeContextParent(job);
    }

    @Transactional
    public void fail(String id, String worker, String reason) {
        var job = owned(id, worker);
        if (job == null) return;
        var call = contexts.latestCall(id);
        boolean dispatched = call != null && "DISPATCHED".equals(call.state());
        if (dispatched) {
            contexts.interruptCall(call.id(), reason, clock.instant());
            operations.settle(job.exerciseId(), call.id(), false);
        }
        String effective = "MODEL_RESULT_UNKNOWN".equals(reason) && !dispatched ? "SERVICE_INTERRUPTED" : reason;
        String cancelled = cancellation(job);
        finish(job, cancelled == null ? "INTERRUPTED" : "CANCELLED", null, cancelled == null ? effective : cancelled);
    }

    /** Returns a standalone worker key to interrupt; inline cancellation waits for the current request to converge. */
    @Transactional
    public String recover(String id) {
        var job = locked(id);
        if (job == null || !("WAITING".equals(job.state()) || "RUNNING".equals(job.state()))) return null;
        contexts.checked(id, clock.instant());
        String cancelled = cancellation(job);
        if ("WAITING".equals(job.state())) {
            if (cancelled != null) finish(job, "CANCELLED", null, cancelled);
            return null;
        }
        if (cancelled != null) contexts.stop(id, cancelled);
        if (job.workerNotAfter() != null && !clock.instant().isBefore(job.workerNotAfter())) {
            var call = contexts.latestCall(id);
            boolean unknown = call != null && "DISPATCHED".equals(call.state());
            if (unknown) {
                contexts.interruptCall(call.id(), "MODEL_RESULT_UNKNOWN", clock.instant());
                operations.settle(job.exerciseId(), call.id(), false);
            }
            finish(job, "INTERRUPTED", null, unknown ? "MODEL_RESULT_UNKNOWN" : "SERVICE_INTERRUPTED");
            return job.workerTaskId() == null ? "summary:" + id : job.workerTaskId();
        }
        return cancelled != null && job.workerTaskId() == null ? "summary:" + id : null;
    }

    private CopilotCompactionEntity locked(String id) {
        var initial = contexts.find(id);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        return contexts.find(id);
    }
    private CopilotCompactionEntity owned(String id, String worker) {
        var job = locked(id);
        if (job == null || !"RUNNING".equals(job.state()) || !worker.equals(job.workerId())
            || job.workerNotAfter() == null || !clock.instant().isBefore(job.workerNotAfter())) return null;
        if (job.workerTaskId() != null) {
            var parent = tasks.find(job.workerTaskId());
            if (parent == null || !worker.equals(parent.workerId()) || parent.workerNotAfter() == null
                || !clock.instant().isBefore(parent.workerNotAfter())) return null;
        }
        return job;
    }
    private boolean settledBatch(String task) {
        var turn = tasks.latestTurn(task);
        return turn == null || !("DISPATCHED".equals(turn.state()) || "TOOLS".equals(turn.state()));
    }
    private String cancellation(CopilotCompactionEntity job) {
        if (job.stopRequested()) return job.failureCode() == null ? "USER_STOP" : job.failureCode();
        var grant = identity.tryAuthorizeMutation(job.sessionHash());
        if (grant == null || !"USER".equals(grant.role()) || !job.userId().equals(grant.userId()) || grant.authVersion() != job.actorAuthVersion()) return "AUTHORIZATION_ENDED";
        var exercise = workspace.exercise(job.exerciseId());
        if (exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) return "EXERCISE_ENDED";
        if (job.boundTaskId() != null) {
            var parent = tasks.find(job.boundTaskId());
            if (parent == null || parent.stopRequested() || "STOPPING".equals(parent.state()) || "STOPPED".equals(parent.state())) return "USER_STOP";
            if ("FAILED".equals(parent.state()) || "INTERRUPTED".equals(parent.state())) return "PARENT_ENDED";
            if (!"SUCCEEDED".equals(parent.state())) {
                var original = identity.tryAuthorizeMutation(parent.sessionHash());
                if (original == null || !"USER".equals(original.role()) || !job.userId().equals(original.userId())) return "AUTHORIZATION_ENDED";
            }
        }
        return null;
    }
    private Instant deadline(CopilotCompactionEntity job) {
        Instant until = clock.instant().plus(Duration.ofSeconds(120));
        Instant exercise = workspace.exercise(job.exerciseId()).deadlineAt();
        Instant session = identity.authorizeMutation(job.sessionHash()).expiresAt();
        if (exercise.isBefore(until)) until = exercise;
        if (session.isBefore(until)) until = session;
        if (job.boundTaskId() != null) {
            var parent = tasks.find(job.boundTaskId());
            if (!"SUCCEEDED".equals(parent.state())) {
                Instant parentSession = identity.authorizeMutation(parent.sessionHash()).expiresAt();
                if (parentSession.isBefore(until)) until = parentSession;
            }
        }
        return until;
    }
    private void finish(CopilotCompactionEntity job, String state, String summary, String reason) {
        if (contexts.finish(job.id(), state, summary, reason, clock.instant()) == 1) {
            operations.settle(job.exerciseId(), job.id(), "APPLIED".equals(state) || "NOT_NEEDED".equals(state));
        }
    }
    private void resumeContextParent(CopilotCompactionEntity job) {
        if (job.boundTaskId() == null) return;
        CopilotTaskEntity parent = tasks.find(job.boundTaskId());
        if (parent != null && "PAUSED".equals(parent.state()) && "COPILOT_CONTEXT_REQUIRED".equals(parent.reason()) && !parent.stopRequested()) {
            tasks.resume(parent.id(), parent.sessionHash());
        }
    }
}
