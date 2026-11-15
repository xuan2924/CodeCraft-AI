package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotSkillResultDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CopilotSkillTools {
    private static final Set<String> FIELDS = Set.of("skill_id", "path", "offset", "limit");
    private final CopilotExecutionStore execution;
    private final SkillSnapshots snapshots;
    private final ObjectMapper json;
    private final ObjectReader arguments;
    public CopilotSkillTools(CopilotExecutionStore execution, SkillSnapshots snapshots, @Qualifier("workspaceJson") ObjectMapper json) {
        this.execution = execution; this.snapshots = snapshots; this.json = json;
        arguments = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public String execute(String taskId, String worker, String input) throws JsonProcessingException {
        try {
            if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid();
            JsonNode value;
            try { value = arguments.readValue(input); }
            catch (JsonProcessingException malformed) { throw invalid(); }
            if (value == null || !value.isObject() || !value.path("skill_id").isTextual()) throw invalid();
            var fields = value.fieldNames();
            while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw invalid();
            String skill = value.path("skill_id").textValue();
            if (!skill.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
            if (value.has("path") && !value.get("path").isTextual()) throw invalid();
            String path = value.has("path") ? value.get("path").textValue() : "SKILL.md";
            int offset = integer(value, "offset", 0); int limit = integer(value, "limit", 65536);
            if (offset < 0 || limit < 128 || limit > 65536) throw invalid();
            var task = execution.current(taskId, worker);
            if (task == null || task.stopRequested()) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
            var source = snapshots.source(task.userId(), task.id(), skill);
            var bundle = json.readValue(source.filesPayload(), SkillPackageRequest.class);
            var file = bundle.files().stream().filter(item -> item.path().equals(path)).findFirst().orElseThrow(CopilotSkillTools::invalid);
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            if (offset > bytes.length || offset < bytes.length && (bytes[offset] & 0xc0) == 0x80) throw invalid();
            int end = offset + Math.min(bytes.length - offset, limit);
            while (end > offset && end < bytes.length && (bytes[end] & 0xc0) == 0x80) end--;
            return json.writeValueAsString(new CopilotSkillResultDTO("OK", skill, source.versionId(), path, offset,
                end == bytes.length ? null : end, bytes.length, end < bytes.length, new String(bytes, offset, end - offset, StandardCharsets.UTF_8)));
        } catch (BusinessException failure) { return json.writeValueAsString(Map.of("code", failure.code())); }
    }
    private int integer(JsonNode value, String field, int fallback) {
        if (!value.has(field)) return fallback;
        if (!value.get(field).isIntegralNumber() || !value.get(field).canConvertToInt()) throw invalid();
        return value.get(field).intValue();
    }
    private static BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
