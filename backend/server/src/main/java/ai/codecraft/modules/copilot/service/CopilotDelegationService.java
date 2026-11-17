package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationInputDTO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.ArrayList;
import java.util.Set;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CopilotDelegationService {
    private static final int GOAL_BYTES = 4096;
    private static final int CONTEXT_BYTES = 8192;
    public static final int MAX_ARGUMENT_BYTES = 6 * (GOAL_BYTES + CONTEXT_BYTES) + 1024;
    private final CopilotDelegationStore store;
    private final StorageCapacityService capacity;
    private final ObjectMapper json;

    public CopilotDelegationService(CopilotDelegationStore store, StorageCapacityService capacity, ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.json = json;
    }

    public CopilotToolExecutionDTO execute(CopilotTaskEntity parent, String worker, CopilotToolEntity tool) throws JsonProcessingException {
        return executeBatch(parent, worker, List.of(tool));
    }

    public boolean explore(String name, String arguments) {
        if (!"delegate_task".equals(name)) return false;
        try { return "EXPLORE".equals(parse("", arguments).mode()); }
        catch (BusinessException | JsonProcessingException invalid) { return false; }
    }

    public CopilotToolExecutionDTO executeBatch(CopilotTaskEntity parent, String worker, List<CopilotToolEntity> tools) throws JsonProcessingException {
        try {
            var inputs = new ArrayList<CopilotDelegationInputDTO>();
            for (var tool : tools) inputs.add(parse(tool.id(), tool.arguments()));
            if (inputs.isEmpty() || inputs.size() > 3 || inputs.size() > 1 && inputs.stream().anyMatch(input -> !"EXPLORE".equals(input.mode()))) {
                throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
            }
            capacity.admit(parent.sessionHash(), parent.exerciseId(), measurement -> {
                store.startBatch(parent.id(), worker, inputs, measurement);
                return true;
            });
            return new CopilotToolExecutionDTO(true, null);
        } catch (BusinessException rejected) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", rejected.errorCode().code(), "childCreated", false)));
        } catch (JsonProcessingException malformed) {
            return new CopilotToolExecutionDTO(false, "{\"code\":\"DELEGATION_INVALID_ARGUMENTS\",\"childCreated\":false}");
        }
    }

    public void recover(String parent) { store.recover(parent); }
    public List<CopilotDelegationResponse> list(String session, String parent) { return store.list(session, parent); }

    private CopilotDelegationInputDTO parse(String tool, String arguments) throws JsonProcessingException {
        if (arguments == null || CopilotContextPlanner.bytes(arguments) > MAX_ARGUMENT_BYTES) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        JsonNode input = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(arguments);
        if (input == null || !input.isObject()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!Set.of("goal", "context", "mode").contains(fields.next())) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String mode = input.has("mode") ? text(input, "mode", 16, true) : "EXECUTE";
        if (!Set.of("EXECUTE", "EXPLORE").contains(mode)) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return new CopilotDelegationInputDTO(tool, text(input, "goal", GOAL_BYTES, true), text(input, "context", CONTEXT_BYTES, false), mode);
    }

    private String text(JsonNode input, String field, int limit, boolean required) {
        JsonNode value = input.get(field);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String text = value.asText();
        if ((required && text.isBlank()) || text.indexOf('\0') >= 0 || CopilotContextPlanner.bytes(text) > limit) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        return text;
    }
}
