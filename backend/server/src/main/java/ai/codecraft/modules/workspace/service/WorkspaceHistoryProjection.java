package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileDifferenceResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryPreviewResponse;
import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.workspace.v1.WorkspaceHistoryPreview;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Validates a read-only sandbox projection before displaying or admitting its exact bytes. */
public final class WorkspaceHistoryProjection {
    private WorkspaceHistoryProjection() {}

    public static WorkspaceHistoryPreviewResponse project(WorkspaceChangeEntity source, long revision, String mode, Long target,
        WorkspaceHistoryPreview preview) {
        if (preview == null || !preview.getWorkspaceInstanceId().equals(source.workspaceInstanceId())
            || preview.getBaseRevision() != source.baseRevision() || preview.getResultRevision() != source.resultRevision()
            || preview.getCurrentRevision() != revision || !preview.getMode().equals(mode)
            || preview.getTargetRevision() != (target == null ? 0 : target)
            || !preview.getFingerprint().equals(fingerprint(preview))
            || preview.getChangesCount() > WorkspaceBatchCodec.MAX_CHANGES || preview.getConflictsCount() > WorkspaceBatchCodec.MAX_CHANGES
            || !"UNDO".equals(mode) && preview.getConflictsCount() != 0) throw invalid();
        var changes = new ArrayList<WorkspaceFileDifferenceResponse>();
        var paths = new HashSet<String>();
        long beforeBytes = 0;
        long afterBytes = 0;
        try {
            for (var item : preview.getChangesList()) {
                if (!item.hasChange()) throw invalid();
                var change = item.getChange();
                WorkspaceBatchCodec.validatePath(change.getPath());
                if (!paths.add(change.getPath()) || !List.of("CREATE", "UPDATE", "DELETE").contains(change.getKind())
                    || "CREATE".equals(change.getKind()) && !item.getPreviousContent().isEmpty()
                    || "DELETE".equals(change.getKind()) && !change.getContent().isEmpty()
                    || "UPDATE".equals(change.getKind()) && item.getPreviousContent().equals(change.getContent())) throw invalid();
                beforeBytes += WorkspaceBatchCodec.contentBytes(item.getPreviousContent());
                afterBytes += WorkspaceBatchCodec.contentBytes(change.getContent());
                changes.add(new WorkspaceFileDifferenceResponse(change.getKind(), change.getPath(), item.getPreviousContent(), change.getContent()));
            }
            if (beforeBytes > WorkspaceBatchCodec.MAX_CONTENT_BYTES || afterBytes > WorkspaceBatchCodec.MAX_CONTENT_BYTES) throw invalid();
            var conflicts = new HashSet<String>();
            for (String path : preview.getConflictsList()) {
                WorkspaceBatchCodec.validatePath(path);
                if (!conflicts.add(path)) throw invalid();
            }
        } catch (BusinessException malformed) { throw new IllegalStateException("Invalid history file projection", malformed); }
        return new WorkspaceHistoryPreviewResponse(source.exerciseId(), source.id(), mode, Long.toString(revision),
            target == null ? null : Long.toString(target), preview.getFingerprint(), List.copyOf(changes), List.copyOf(preview.getConflictsList()));
    }

    private static String fingerprint(WorkspaceHistoryPreview preview) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preview.toBuilder().clearFingerprint().build().toByteArray()));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Invalid history projection"); }
}
