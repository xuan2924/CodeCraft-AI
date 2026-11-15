package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.FileChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

@Component
public class WorkspaceBatchCodec {
    public static final int MAX_CHANGES = 400;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_CONTENT_BYTES = 10 * 1024 * 1024;
    private final ObjectMapper json;
    public WorkspaceBatchCodec(@Qualifier("workspaceJson") ObjectMapper json) { this.json = json; }

    public String encode(FileBatchRequest input) {
        if (input == null || input.expectedRevision() == null || !input.expectedRevision().matches("0|[1-9][0-9]{0,18}")) invalid();
        try { Long.parseLong(input.expectedRevision()); } catch (NumberFormatException error) { invalid(); }
        if (input.changes() == null || input.changes().isEmpty() || input.changes().size() > MAX_CHANGES) invalid();
        Set<String> paths = new HashSet<>();
        List<FileChangeRequest> changes = new ArrayList<>();
        long bytes = 0;
        for (FileChangeRequest change : input.changes()) {
            if (change == null) invalid();
            validatePath(change.path());
            if (!paths.add(change.path()) || change.kind() == null || !List.of("CREATE", "UPDATE", "DELETE").contains(change.kind())) invalid();
            String content = change.content() == null ? "" : change.content();
            if (change.kind().equals("DELETE") && !content.isEmpty()) invalid();
            bytes += contentBytes(content);
            if (bytes > MAX_CONTENT_BYTES) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_CAPACITY);
            changes.add(new FileChangeRequest(change.path(), change.kind(), content));
        }
        changes.sort(Comparator.comparing(FileChangeRequest::path));
        try { return json.writeValueAsString(new FileBatchRequest(input.expectedRevision(), List.copyOf(changes))); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode validated batch", error); }
    }

    public ApplyBatchRequest request(WorkspaceOperationEntity operation, WorkspaceMutationEntity mutation) {
        try {
            FileBatchRequest batch = json.readValue(mutation.payload(), FileBatchRequest.class);
            ApplyBatchRequest.Builder request = ApplyBatchRequest.newBuilder().setWorkspaceInstanceId(operation.workspaceInstanceId())
                .setRequestId(operation.id()).setOperationId(operation.id()).setGateEpoch(mutation.gateEpoch())
                .setNotAfterUnixMillis(mutation.notAfter().toEpochMilli()).setExpectedRevision(Long.parseLong(batch.expectedRevision()));
            for (FileChangeRequest change : batch.changes()) request.addChanges(FileChange.newBuilder()
                .setPath(change.path()).setKind(change.kind()).setContent(change.content()));
            return request.build();
        } catch (JsonProcessingException error) { throw new IllegalStateException("Stored batch is invalid", error); }
    }

    /** Shared managed-path policy for writes and read-only recovery projections. */
    public static void validatePath(String path) {
        if (path == null || path.length() > 4096 || !path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
            || List.of(path.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) invalid();
        if (!path.startsWith("src/main/java/") && !path.startsWith("src/test/java/")) throw new BusinessException(WorkspaceErrorCode.PATH_FORBIDDEN);
    }

    /** Validates text without interpreting it and returns its UTF-8 storage size. */
    public static int contentBytes(String content) {
        if (content == null || content.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(content)) invalid();
        int size = content.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_FILE_BYTES) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_CAPACITY);
        return size;
    }

    private static void invalid() { throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST); }
}
