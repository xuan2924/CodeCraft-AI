package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotFileDiffDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalInputDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalPreparedDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Prepares immutable evidence through authorized reads; never applies a change or holds a database transaction. */
@Service
public class CopilotProposalPreparation {
    public static final int MAX_ARGUMENT_BYTES = 192 * 1024;
    public static final long PREPARATION_CREDIT = 64L * 1024 * 1024;
    private final ExerciseService files;
    private final WorkspaceBatchCodec codec;
    private final ObjectMapper json;
    private final ObjectReader input;

    public CopilotProposalPreparation(ExerciseService files, WorkspaceBatchCodec codec, @Qualifier("workspaceJson") ObjectMapper json) {
        this.files = files; this.codec = codec; this.json = json;
        input = json.readerFor(CopilotProposalInputDTO.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public CopilotProposalPreparedDTO prepare(String session, String exercise, String arguments) {
        if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) invalid();
        FileBatchRequest batch;
        String payload;
        try {
            CopilotProposalInputDTO request = input.readValue(arguments);
            if (request == null || request.changes() == null || request.changes().size() > 32) invalid();
            payload = codec.encode(new FileBatchRequest(request.expectedRevision(), request.changes()));
            batch = json.readValue(payload, FileBatchRequest.class);
        } catch (JsonProcessingException malformed) { throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
        var initial = files.find(session, exercise);
        requireBaseline(initial, initial.workspaceInstanceId(), batch.expectedRevision());
        var diffs = new ArrayList<CopilotFileDiffDTO>();
        long originalBytes = 0;
        for (var change : batch.changes()) {
            boolean exists = initial.paths().contains(change.path());
            boolean creating = change.kind().equals("CREATE");
            if (creating && exists) throw new BusinessException(WorkspaceErrorCode.FILE_EXISTS);
            if (!creating && !exists) throw new BusinessException(WorkspaceErrorCode.FILE_NOT_FOUND);
            String before = null;
            if (!creating) {
                var file = files.read(session, exercise, change.path());
                if (!initial.workspaceInstanceId().equals(file.workspaceInstanceId()) || !batch.expectedRevision().equals(file.revision())
                    || !change.path().equals(file.path())) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
                before = file.content();
                int bytes = before.getBytes(StandardCharsets.UTF_8).length;
                originalBytes += bytes;
                if (bytes > 1024 * 1024 || originalBytes > 10 * 1024 * 1024) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_CAPACITY);
            }
            diffs.add(new CopilotFileDiffDTO(change.path(), change.kind(), before, change.kind().equals("DELETE") ? null : change.content()));
        }
        requireBaseline(files.find(session, exercise), initial.workspaceInstanceId(), batch.expectedRevision());
        return new CopilotProposalPreparedDTO(initial.workspaceInstanceId(), batch.expectedRevision(), payload, diffs);
    }

    private void requireBaseline(ExerciseResponse exercise, String instance, String revision) {
        if (!"ACTIVE".equals(exercise.state())) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!instance.equals(exercise.workspaceInstanceId()) || !revision.equals(exercise.revision())) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
    }
    private void invalid() { throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
