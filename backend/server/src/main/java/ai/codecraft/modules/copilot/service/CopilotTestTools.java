package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotTestInputDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotTestResultDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotTestResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Codecs and capacity measurement run outside authorization/commit transactions. */
@Service
public class CopilotTestTools {
    private final CopilotTestStore store;
    private final StorageCapacityService capacity;
    private final CopilotTestResultFormatter formatter;
    private final ObjectMapper json;
    private final ObjectReader input;
    public CopilotTestTools(CopilotTestStore store, StorageCapacityService capacity, CopilotTestResultFormatter formatter,
        @Qualifier("workspaceJson") ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.formatter = formatter; this.json = json;
        input = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public CopilotToolExecutionDTO execute(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException {
        try {
            var request = parse(tool.arguments());
            String cached = store.cached(task.id(), worker, request);
            if (cached != null) {
                var result = json.readValue(cached, CopilotTestResultDTO.class);
                return new CopilotToolExecutionDTO(false, json.writeValueAsString(new CopilotTestResultDTO(result.code(), true,
                    result.result(), result.logTruncated(), result.omittedLogBytes())));
            }
            capacity.admitHeavy(task.sessionHash(), task.exerciseId(), measurement -> {
                store.start(task.id(), worker, tool.id(), request, measurement);
                return null;
            });
            return new CopilotToolExecutionDTO(true, null);
        } catch (BusinessException rejected) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", rejected.errorCode().code())));
        }
    }

    public void recoverTask(String task) {
        var source = store.recovery(task);
        if (source == null) return;
        try {
            var result = json.readValue(source.evidence().payload(), TestTaskResponse.class);
            var child = source.binding();
            if (!child.testTaskId().equals(result.taskId()) || !child.exerciseId().equals(result.exerciseId())
                || !child.workspaceInstanceId().equals(result.workspaceInstanceId()) || !Long.toString(child.revision()).equals(result.revision())
                || !child.target().equals(result.target()) || !source.evidence().state().equals(result.state())) {
                throw new IllegalStateException("Test result does not match its parent binding");
            }
            store.complete(source, json.writeValueAsString(formatter.format(result, false)));
        }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot preserve confirmed test result"); }
    }

    public List<CopilotTestResponse> recent(String session, String task) { return store.recent(session, task); }

    CopilotTestInputDTO parse(String arguments) {
        if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid();
        try {
            JsonNode value = input.readValue(arguments);
            if (!value.isObject() || value.size() != 2 || !value.path("expected_revision").isTextual() || !value.path("target").isTextual()) throw invalid();
            String revision = value.get("expected_revision").textValue();
            if (!revision.matches("0|[1-9][0-9]{0,18}") || !Set.of("base", "challenge").contains(value.get("target").textValue())) throw invalid();
            Long.parseLong(revision);
            return new CopilotTestInputDTO(revision, value.get("target").textValue());
        } catch (JsonProcessingException | NumberFormatException rejected) { throw invalid(); }
    }

    private BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
