package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationResponse;
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
        try {
            JsonNode input = json.readerFor(JsonNode.class).readValue(tool.arguments());
            if (input == null || !input.isObject()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
            String goal = text(input, "goal", GOAL_BYTES, true);
            String context = text(input, "context", CONTEXT_BYTES, false);
            capacity.admit(parent.sessionHash(), parent.exerciseId(), measurement -> {
                store.start(parent.id(), worker, tool.id(), goal, context, measurement);
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
