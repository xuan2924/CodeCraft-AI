package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.workspace.model.dto.EvaluationReportDTO;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceSummaryDTO;
import ai.codecraft.modules.workspace.model.dto.ReportReviewRequest;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseReportRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable review/frozen-version authority plus bounded, recoverable evaluator dispatch. */
@Service
public class ExerciseReportStore {
    private static final long GROWTH = 2 * 1024 * 1024;
    private final ExerciseReportRepository reports;
    private final ExerciseRepository exercises;
    private final FinalizationRepository finals;
    private final IdentityTransactionService identity;
    private final PlatformBindingService platform;
    private final StorageCapacityStore capacity;
    private final IdentitySecrets secrets;
    private final ObjectMapper json;
    private final Clock clock;

    public ExerciseReportStore(ExerciseReportRepository reports, ExerciseRepository exercises, FinalizationRepository finals,
        IdentityTransactionService identity, PlatformBindingService platform, StorageCapacityStore capacity, IdentitySecrets secrets,
        @Qualifier("workspaceJson") ObjectMapper json, Clock clock) {
        this.reports = reports; this.exercises = exercises; this.finals = finals; this.identity = identity; this.platform = platform;
        this.capacity = capacity; this.secrets = secrets; this.json = json; this.clock = clock;
    }
    @Transactional
    public ExerciseReportResponse view(String session, String exercise) {
        var current = owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        return describe(current, reports.find(exercise));
    }
    @Transactional
    public ReportEvidenceDTO evidence(String session, String exercise, long generation, String id) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        var report = reports.find(exercise);
        if (report == null || report.generation() != generation) fail(WorkspaceErrorCode.REVISION_CONFLICT);
        return evidence(report).stream().filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
    }
    @Transactional
    public boolean existing(String session, String exercise, String key, String kind, String fingerprint) {
        var current = owned(identity.authorizeMutation(session).userId(), exercise);
        key(key);
        var previous = exercises.receipt(current.userId(), kind, exercise, key);
        if (previous != null && !previous.fingerprint().equals(secrets.fingerprint(fingerprint))) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return previous != null;
    }
    public String reviewFingerprint(ReportReviewRequest input) {
        if (input == null || input.text() == null || input.text().getBytes(StandardCharsets.UTF_8).length > 4096
            || input.skip() && !input.text().isEmpty() || !input.skip() && input.text().isBlank()
            || input.text().codePoints().anyMatch(point -> point == 0 || point >= 0xD800 && point <= 0xDFFF)) fail(WorkspaceErrorCode.INVALID_REQUEST);
        return input.skip() + "\n" + input.text();
    }
    @Transactional
    public ExerciseReportResponse review(String session, String exercise, String key, ReportReviewRequest input, StorageMeasurement measurement) {
        String fingerprint = reviewFingerprint(input);
        var current = owned(identity.authorizeMutation(session).userId(), exercise);
        key(key);
        var previous = exercises.receipt(current.userId(), "REPORT_REVIEW", exercise, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(secrets.fingerprint(fingerprint))) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return describe(current, reports.find(exercise));
        }
        var frozen = finals.find(exercise);
        if (!"ENDED".equals(current.state()) || frozen == null || frozen.finalRevision() == null || frozen.endedAt() == null || reports.find(exercise) != null) fail(WorkspaceErrorCode.INVALID_STATE);
        var version = platform.version(exercise, PlatformRole.EVALUATOR);
        String id = UUID.randomUUID().toString();
        var operation = operation(current, id, "REPORT_REVIEW", key, fingerprint);
        capacity.admit(operation, measurement, GROWTH); exercises.insertOperation(operation);
        var report = new ExerciseReportEntity(exercise, id, current.userId(), session, version.id(), frozen.finalRevision(), frozen.verification(), frozen.resultTaskId(),
            input.skip(), input.text(), id, 1, "QUEUED", 0, null, null, null, null, null, clock.instant(), clock.instant());
        reports.insert(report);
        return describe(current, report);
    }
    @Transactional
    public ExerciseReportResponse retry(String session, String exercise, String key, long generation, StorageMeasurement measurement) {
        var current = owned(identity.authorizeMutation(session).userId(), exercise);
        key(key);
        var previous = exercises.receipt(current.userId(), "REPORT_RETRY", exercise, key);
        String fingerprint = Long.toString(generation);
        if (previous != null) {
            if (!previous.fingerprint().equals(secrets.fingerprint(fingerprint))) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return describe(current, reports.find(exercise));
        }
        var report = reports.find(exercise);
        if (report == null || report.generation() != generation || !List.of("FAILED", "UNKNOWN").contains(report.state())) fail(WorkspaceErrorCode.INVALID_STATE);
        if (!platform.version(exercise, PlatformRole.EVALUATOR).id().equals(report.platformVersionId())) fail(WorkspaceErrorCode.INVALID_STATE);
        String id = UUID.randomUUID().toString();
        var operation = operation(current, id, "REPORT_RETRY", key, fingerprint);
        capacity.admit(operation, measurement, GROWTH); exercises.insertOperation(operation);
        if (reports.retry(exercise, generation, session, id, clock.instant()) != 1) throw new IllegalStateException("Report retry lost owner lock");
        return describe(current, reports.find(exercise));
    }
    @Transactional(readOnly = true)
    public List<String> pending() { return reports.pending(clock.instant()); }
    @Transactional
    public ExerciseReportEntity prepare(String exercise) {
        var initial = reports.find(exercise);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var report = reports.find(exercise);
        if (report == null) return null;
        if ("RUNNING".equals(report.state())) {
            if (!clock.instant().isBefore(report.deadlineAt())) {
                reports.finishAttempt(report.attemptId(), "UNKNOWN", clock.instant(), null, null, "EVALUATION_RESULT_UNKNOWN");
                finish(report, "UNKNOWN", null, "EVALUATION_RESULT_UNKNOWN");
            }
            return null;
        }
        if (!List.of("QUEUED", "PREPARING").contains(report.state())) return null;
        if (!authorized(report)) { finish(report, "FAILED", null, "EVALUATION_AUTHORIZATION_CHANGED"); return null; }
        if (reports.prepare(exercise, UUID.randomUUID().toString(), clock.instant(), clock.instant().plusSeconds(120)) != 1) return null;
        return reports.find(exercise);
    }
    @Transactional
    public Instant dispatch(ExerciseReportEntity ticket, List<ReportEvidenceDTO> evidence, String price) {
        identity.lockResourceOwner(ticket.userId());
        var current = reports.find(ticket.exerciseId());
        if (!sameAttempt(current, ticket) || !"PREPARING".equals(current.state()) || !clock.instant().isBefore(current.deadlineAt())) return null;
        if (!authorized(current)) { finish(current, "FAILED", null, "EVALUATION_AUTHORIZATION_CHANGED"); return null; }
        String encoded = encode(evidence);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 262144) throw new IllegalArgumentException("Report evidence exceeds budget");
        var auth = identity.tryAuthorizeMutation(current.sessionHash());
        Instant deadline = clock.instant().plusSeconds(120);
        if (auth.expiresAt().isBefore(deadline)) deadline = auth.expiresAt();
        if (reports.dispatch(current.exerciseId(), current.generation(), current.attemptId(), encoded, clock.instant(), deadline) != 1) return null;
        reports.attempt(current.attemptId(), current.exerciseId(), current.generation(), clock.instant(), price);
        return deadline;
    }
    @Transactional
    public void complete(ExerciseReportEntity ticket, EvaluationReportDTO evaluation, String raw, String usage, String reason, boolean regenerate) {
        identity.lockResourceOwner(ticket.userId());
        var current = reports.find(ticket.exerciseId());
        if (!sameAttempt(current, ticket) || !List.of("RUNNING", "PREPARING").contains(current.state())) {
            // Accounting may reconcile a known late receipt; it must never revive or publish an old result.
            if (current != null && usage != null) reports.lateUsage(ticket.attemptId(), ticket.exerciseId(), ticket.generation(), usage);
            return;
        }
        if (!authorized(current)) { reason = "EVALUATION_AUTHORIZATION_CHANGED"; regenerate = false; evaluation = null; }
        if (!clock.instant().isBefore(current.deadlineAt())) {
            reason = "RUNNING".equals(current.state()) ? "EVALUATION_RESULT_UNKNOWN" : "EVALUATION_PREPARATION_FAILED";
            regenerate = false; evaluation = null;
        }
        String state = evaluation != null ? "SUCCEEDED" : "EVALUATION_RESULT_UNKNOWN".equals(reason) ? "UNKNOWN" : "FAILED";
        if (raw != null && (raw.indexOf('\0') >= 0 || raw.getBytes(StandardCharsets.UTF_8).length > 65536)) raw = null;
        if ("RUNNING".equals(current.state())) reports.finishAttempt(current.attemptId(), state, clock.instant(), usage, raw, reason);
        if (regenerate && evaluation == null && current.attempts() < 3 && "RUNNING".equals(current.state())) state = "QUEUED";
        finish(current, state, evaluation == null ? null : encode(evaluation), reason);
    }
    public List<ReportEvidenceDTO> evidence(ExerciseReportEntity report) {
        if (report.evidence() == null) return List.of();
        try { return json.readValue(report.evidence(), new TypeReference<List<ReportEvidenceDTO>>() {}); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid persisted report evidence", error); }
    }
    private boolean sameAttempt(ExerciseReportEntity current, ExerciseReportEntity ticket) {
        return current != null && current.generation() == ticket.generation() && ticket.attemptId().equals(current.attemptId());
    }
    private boolean authorized(ExerciseReportEntity report) {
        var auth = identity.tryAuthorizeMutation(report.sessionHash());
        var current = exercises.find(report.exerciseId());
        var frozen = finals.find(report.exerciseId());
        return auth != null && auth.userId().equals(report.userId()) && current != null && !exercises.deleted(report.exerciseId())
            && "ENDED".equals(current.state()) && frozen != null && frozen.finalRevision() != null && frozen.finalRevision() == report.finalRevision()
            && platform.version(report.exerciseId(), PlatformRole.EVALUATOR).id().equals(report.platformVersionId());
    }
    private void finish(ExerciseReportEntity report, String state, String evaluation, String reason) {
        reports.finish(report.exerciseId(), report.generation(), state, evaluation, reason, clock.instant());
        if (!"QUEUED".equals(state)) exercises.operationState(report.operationId(), "SUCCEEDED".equals(state) ? "SUCCEEDED" : "REJECTED");
    }
    private WorkspaceOperationEntity operation(ExerciseEntity current, String id, String kind, String key, String input) {
        return new WorkspaceOperationEntity(id, current.userId(), current.id(), current.workspaceInstanceId(), kind, current.id(), key, secrets.fingerprint(input), "PENDING", clock.instant());
    }
    private ExerciseReportResponse describe(ExerciseEntity exercise, ExerciseReportEntity report) {
        if (report == null) {
            var frozen = finals.find(exercise.id());
            return new ExerciseReportResponse(exercise.id(), null, 0, "ENDED".equals(exercise.state()) && frozen != null && frozen.finalRevision() != null ? "WAITING_REVIEW" : "NOT_READY",
                frozen == null || frozen.finalRevision() == null ? null : frozen.finalRevision().toString(), frozen == null ? "PENDING" : frozen.verification(),
                frozen == null ? null : frozen.resultTaskId(), null, null, 0, null, null, List.of());
        }
        EvaluationReportDTO evaluation = null;
        if (report.evaluation() != null) {
            try { evaluation = json.readValue(report.evaluation(), EvaluationReportDTO.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Invalid stored evaluation", error); }
        }
        return new ExerciseReportResponse(exercise.id(), report.id(), report.generation(), report.state(), Long.toString(report.finalRevision()), report.verification(), report.finalTestId(),
            report.reviewSkipped(), report.platformVersionId(), report.attempts(), report.reason(), evaluation,
            evidence(report).stream().map(item -> new ReportEvidenceSummaryDTO(item.id(), item.kind(), item.source(), item.summary(), item.truncated())).toList());
    }
    private ExerciseEntity owned(String user, String exercise) {
        var current = exercises.find(exercise);
        if (current == null || !current.userId().equals(user)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exercise)) fail(WorkspaceErrorCode.EXERCISE_DELETED);
        return current;
    }
    private void key(String key) { if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) fail(WorkspaceErrorCode.INVALID_REQUEST); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode report data", error); }
    }
    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
