package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.FeedbackPreviewDTO;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.FeedbackRequest;
import ai.codecraft.modules.workspace.model.dto.FeedbackResponse;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FeedbackEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FeedbackRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackStore {
    private final FeedbackRepository feedback;
    private final ExerciseRepository exercises;
    private final ExerciseReportStore reports;
    private final InterviewerStore interviewer;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final StorageCapacityStore capacity;
    private final Clock clock;
    public FeedbackStore(FeedbackRepository feedback, ExerciseRepository exercises, ExerciseReportStore reports, InterviewerStore interviewer,
        IdentityTransactionService identity, IdentitySecrets secrets, StorageCapacityStore capacity, Clock clock) {
        this.feedback = feedback; this.exercises = exercises; this.reports = reports; this.interviewer = interviewer;
        this.identity = identity; this.secrets = secrets; this.capacity = capacity; this.clock = clock;
    }
    @Transactional
    public FeedbackPreviewDTO preview(String session, String exercise, FeedbackRequest input) {
        owned(session, exercise);
        return snapshot(session, exercise, input);
    }
    @Transactional
    public FeedbackResponse existing(String session, String exercise, String key, FeedbackRequest input) {
        owned(session, exercise); key(key); validate(input);
        var previous = feedback.receipt(exercise, key);
        if (previous == null) return null;
        if (!previous.requestFingerprint().equals(secrets.fingerprint(reports.encode(input)))) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return response(previous);
    }
    @Transactional
    public FeedbackResponse submit(String session, String exercise, String key, FeedbackRequest input, StorageMeasurement measurement) {
        var current = owned(session, exercise); key(key); validate(input);
        var previous = feedback.receipt(exercise, key);
        String fingerprint = secrets.fingerprint(reports.encode(input));
        if (previous != null) {
            if (!previous.requestFingerprint().equals(fingerprint)) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return response(previous);
        }
        if (feedback.count(exercise) >= 20) fail(WorkspaceErrorCode.WORKSPACE_CAPACITY);
        var preview = snapshot(session, exercise, input);
        if (!preview.fingerprint().equals(input.previewFingerprint())) fail(WorkspaceErrorCode.REVISION_CONFLICT);
        String id = UUID.randomUUID().toString();
        var operation = new WorkspaceOperationEntity(id, current.userId(), exercise, current.workspaceInstanceId(), "FEEDBACK", exercise, key, fingerprint, "PENDING", clock.instant());
        capacity.admit(operation, measurement, 131072); exercises.insertOperation(operation);
        var result = new FeedbackEntity(id, exercise, current.userId(), key, fingerprint, preview.snapshot(), "PENDING", null, null, clock.instant(), null);
        feedback.insert(result); exercises.operationState(id, "SUCCEEDED");
        return response(result);
    }
    @Transactional
    public List<FeedbackResponse> list(String session, boolean admin, String after) {
        var actor = identity.authorizePrivateDelivery(session);
        if (admin && !"ADMIN".equals(actor.role()) || !admin && !"USER".equals(actor.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        cursor(after);
        return feedback.list(admin ? null : actor.userId(), after, clock.instant()).stream().map(this::response).toList();
    }
    @Transactional
    public FeedbackResponse reply(String session, String id, String key, String reply) {
        var actor = identity.authorizeMutation(session);
        if (!"ADMIN".equals(actor.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        key(key); text(reply); cursor(id);
        var initial = feedback.find(id);
        if (initial == null) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        identity.lockResourceOwner(initial.userId());
        var current = feedback.find(id);
        if (current == null || !feedback.live(current.exerciseId(), clock.instant())) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if ("HANDLED".equals(current.state())) {
            if (!key.equals(current.replyKey()) || !reply.equals(current.reply())) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return response(current);
        }
        if (feedback.reply(id, key, reply, clock.instant()) != 1) fail(WorkspaceErrorCode.INVALID_STATE);
        return response(feedback.find(id));
    }
    private FeedbackPreviewDTO snapshot(String session, String exercise, FeedbackRequest input) {
        validate(input);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("targetType", input.targetType()); snapshot.put("targetId", input.targetId()); snapshot.put("description", input.description());
        snapshot.put("retention", "与原演练同保留期限和容量；删除或到期即撤销双方读取，快照、附件及回复随后一并清理。不承诺改分或更改配置。");
        List<ReportEvidenceDTO> selected = new ArrayList<>();
        if ("REPORT".equals(input.targetType())) {
            var report = reports.view(session, exercise);
            if (!input.targetId().equals(report.reportId()) || input.generation() != report.generation()) fail(WorkspaceErrorCode.REVISION_CONFLICT);
            snapshot.put("generation", report.generation()); snapshot.put("platformVersionId", report.platformVersionId());
            snapshot.put("finalRevision", report.finalRevision()); snapshot.put("verification", report.verification());
            snapshot.put("state", report.state()); snapshot.put("evaluation", report.evaluation());
            for (String id : input.evidenceIds()) selected.add(reports.evidence(session, exercise, input.generation(), id));
        } else {
            if (!input.evidenceIds().isEmpty() || input.generation() != 0) fail(WorkspaceErrorCode.INVALID_REQUEST);
            var question = interviewer.find(session, exercise, input.targetId());
            snapshot.put("platformVersionId", question.platformVersionId()); snapshot.put("question", question.question()); snapshot.put("answer", question.answer()); snapshot.put("state", question.state());
        }
        snapshot.put("evidence", selected);
        String encoded = reports.encode(snapshot);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65536) fail(WorkspaceErrorCode.INVALID_REQUEST);
        return new FeedbackPreviewDTO(encoded, secrets.fingerprint(encoded));
    }
    private ExerciseEntity owned(String session, String exercise) {
        var actor = identity.authorizeMutation(session);
        if (!"USER".equals(actor.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        var current = exercises.find(exercise);
        if (current == null || !current.userId().equals(actor.userId()) || !feedback.live(exercise, clock.instant())) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return current;
    }
    private void validate(FeedbackRequest input) {
        if (input == null || input.targetType() == null || !List.of("REPORT", "INTERVIEWER").contains(input.targetType()) || input.targetId() == null || input.evidenceIds() == null
            || input.previewFingerprint() != null && input.previewFingerprint().length() > 128 || input.evidenceIds().size() > 4 || input.evidenceIds().stream().anyMatch(value -> value == null || value.length() > 2048)
            || new HashSet<>(input.evidenceIds()).size() != input.evidenceIds().size()) fail(WorkspaceErrorCode.INVALID_REQUEST);
        cursor(input.targetId()); text(input.description());
    }
    private void text(String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 4096
            || value.codePoints().anyMatch(point -> point == 0 || point >= 0xD800 && point <= 0xDFFF)) fail(WorkspaceErrorCode.INVALID_REQUEST);
    }
    private void cursor(String value) { if (value != null) { try { UUID.fromString(value); } catch (IllegalArgumentException error) { fail(WorkspaceErrorCode.INVALID_REQUEST); } } }
    private void key(String key) { if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) fail(WorkspaceErrorCode.INVALID_REQUEST); }
    private FeedbackResponse response(FeedbackEntity value) { return new FeedbackResponse(value.id(), value.snapshot(), value.state(), value.reply(), value.createdAt(), value.handledAt()); }
    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
