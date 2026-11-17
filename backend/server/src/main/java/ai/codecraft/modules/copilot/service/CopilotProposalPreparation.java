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
import java.util.Set;
import ai.codecraft.modules.copilot.model.dto.CopilotTextReplacementDTO;
import java.util.Comparator;
import com.fasterxml.jackson.databind.JsonNode;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
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

    public CopilotProposalPreparedDTO prepare(String session, String exercise, String name, String arguments) {
        if ("propose_changes".equals(name)) return prepare(session, exercise, arguments);
        if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) invalid();
        try {
            JsonNode value = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(arguments);
            fields(value, "write".equals(name) ? Set.of("path", "content", "expected_revision") : Set.of("files", "expected_revision"));
            String revision = text(value, "expected_revision");
            var snapshot = files.find(session, exercise);
            requireBaseline(snapshot, snapshot.workspaceInstanceId(), revision);
            var changes = new ArrayList<FileChangeRequest>();
            if ("write".equals(name)) {
                String path = text(value, "path");
                changes.add(new FileChangeRequest(path, snapshot.paths().contains(path) ? "UPDATE" : "CREATE", text(value, "content")));
            } else {
                if (!"edit".equals(name) || !value.path("files").isArray() || value.path("files").isEmpty() || value.path("files").size() > 32) invalid();
                for (var file : value.path("files")) {
                    fields(file, Set.of("path", "action", "content", "edits"));
                    String path = text(file, "path");
                    String action = text(file, "action");
                    if ("CREATE".equals(action)) {
                        if (file.has("edits")) invalid();
                        changes.add(new FileChangeRequest(path, "CREATE", text(file, "content")));
                    } else if ("DELETE".equals(action)) {
                        if (file.has("content") || file.has("edits")) invalid();
                        changes.add(new FileChangeRequest(path, "DELETE", null));
                    } else if ("EDIT".equals(action)) {
                        if (file.has("content") || !file.path("edits").isArray() || file.path("edits").isEmpty() || file.path("edits").size() > 200) invalid();
                        if (!snapshot.paths().contains(path)) throw new BusinessException(WorkspaceErrorCode.FILE_NOT_FOUND);
                        var original = files.read(session, exercise, path);
                        if (!revision.equals(original.revision()) || !snapshot.workspaceInstanceId().equals(original.workspaceInstanceId())) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
                        changes.add(new FileChangeRequest(path, "UPDATE", exactEdit(path, original.content(), file.path("edits"))));
                    } else invalid();
                }
            }
            // The normal immutable proposal path rechecks all files and the revision after preparation.
            return prepareBatch(session, exercise, json.writeValueAsString(new CopilotProposalInputDTO(revision, changes)));
        } catch (JsonProcessingException malformed) { throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
    }

    static String exactEdit(String path, String original, JsonNode edits) {
        String source = original.replace("\r\n", "\n");
        int lineCount = source.split("\n", -1).length;
        var replacements = new ArrayList<CopilotTextReplacementDTO>();
        for (var edit : edits) {
            fields(edit, Set.of("oldText", "newText"));
            String old = text(edit, "oldText").replace("\r\n", "\n");
            String replacement = text(edit, "newText").replace("\r\n", "\n");
            if (old.isEmpty()) throw new CopilotEditException("EDIT_EMPTY_MATCH", path, lineCount);
            int index = source.indexOf(old);
            if (index < 0) throw new CopilotEditException("EDIT_MATCH_NOT_FOUND", path, lineCount);
            if (source.indexOf(old, index + 1) >= 0) throw new CopilotEditException("EDIT_AMBIGUOUS_MATCH", path, lineCount);
            replacements.add(new CopilotTextReplacementDTO(index, index + old.length(), replacement));
        }
        replacements.sort(Comparator.comparingInt(CopilotTextReplacementDTO::start));
        int end = 0;
        var output = new StringBuilder();
        for (var replacement : replacements) {
            int start = replacement.start();
            if (start < end) throw new CopilotEditException("EDIT_OVERLAPPING_MATCH", path, lineCount);
            output.append(source, end, start).append(replacement.text());
            end = replacement.end();
        }
        output.append(source, end, source.length());
        return original.contains("\r\n") ? output.toString().replace("\n", "\r\n") : output.toString();
    }

    private static void fields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject()) invalid();
        var names = value.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) invalid();
    }
    private static String text(JsonNode value, String name) {
        if (!value.path(name).isTextual()) invalid();
        return value.path(name).textValue();
    }

    public CopilotProposalPreparedDTO prepare(String session, String exercise, String arguments) {
        if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) invalid();
        return prepareBatch(session, exercise, arguments);
    }

    private CopilotProposalPreparedDTO prepareBatch(String session, String exercise, String arguments) {
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
        try { return new CopilotProposalPreparedDTO(initial.workspaceInstanceId(), batch.expectedRevision(), payload, json.writeValueAsString(diffs), diffs); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Proposal evidence cannot be encoded"); }
    }

    private void requireBaseline(ExerciseResponse exercise, String instance, String revision) {
        if (!"ACTIVE".equals(exercise.state())) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!instance.equals(exercise.workspaceInstanceId()) || !revision.equals(exercise.revision())) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
    }
    private static void invalid() { throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
