package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.ReportDocumentDTO;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.ReportReviewRequest;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class ExerciseReportService {
    private final ExerciseReportStore store;
    private final StorageCapacityService capacity;
    private final ReportPdfRenderer pdf;
    private final Semaphore exports;
    public ExerciseReportService(ExerciseReportStore store, StorageCapacityService capacity, ReportPdfRenderer pdf, PlatformAgentProperties policy) {
        this.store = store; this.capacity = capacity; this.pdf = pdf; exports = new Semaphore(policy.workers());
    }
    public ExerciseReportResponse view(String session, String exercise) { return store.view(session, exercise); }
    public ExerciseReportResponse review(String session, String exercise, String key, ReportReviewRequest request) {
        String fingerprint = store.reviewFingerprint(request);
        if (store.existing(session, exercise, key, "REPORT_REVIEW", fingerprint)) return store.view(session, exercise);
        return capacity.admit(session, exercise, measurement -> store.review(session, exercise, key, request, measurement));
    }
    public ExerciseReportResponse retry(String session, String exercise, String key, long generation) {
        if (generation < 1) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if (store.existing(session, exercise, key, "REPORT_RETRY", Long.toString(generation))) return store.view(session, exercise);
        return capacity.admit(session, exercise, measurement -> store.retry(session, exercise, key, generation, measurement));
    }
    public ReportEvidenceDTO evidence(String session, String exercise, long generation, String id) { return store.evidence(session, exercise, generation, id); }
    public byte[] pdf(String session, String exercise, long generation) throws IOException {
        var snapshot = store.view(session, exercise);
        if (snapshot.generation() != generation) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        if (snapshot.finalRevision() == null) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!exports.tryAcquire()) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_CAPACITY);
        try {
            byte[] bytes = pdf.render(new ReportDocumentDTO(exercise, snapshot.reportId(), snapshot.generation(), snapshot.finalRevision(), snapshot.verification(),
                snapshot.state(), Boolean.TRUE.equals(snapshot.reviewSkipped()), snapshot.evaluation(), snapshot.evidence()));
            // Private bytes are not returned before ownership/deletion and exact visible generation are checked again.
            var current = store.view(session, exercise);
            if (!Objects.equals(current, snapshot)) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
            return bytes;
        } finally { exports.release(); }
    }
}
