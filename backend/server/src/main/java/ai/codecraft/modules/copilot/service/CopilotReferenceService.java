package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotReferencePageDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotReferenceSourcesDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CopilotReferenceService {
    private final CopilotReferences references;
    private final CopilotConversationStore conversations;
    private final ObjectMapper json;
    private final ObjectReader reader;
    public CopilotReferenceService(CopilotReferences references, CopilotConversationStore conversations, ObjectMapper json) {
        this.references = references; this.conversations = conversations; this.json = json;
        reader = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public CopilotReferencePageDTO preview(String session, String exercise, String conversation, String kind, String source, String version, int offset, int limit) {
        conversations.resolve(session, exercise, conversation);
        return references.page(session, exercise, kind, source, version, offset, limit);
    }
    public CopilotReferenceSourcesDTO sources(String session, String exercise, String conversation, String kind, String after) {
        conversations.resolve(session, exercise, conversation);
        return references.sources(session, exercise, kind, after);
    }
    public String execute(String session, String exercise, String arguments) throws JsonProcessingException {
        try {
            if (arguments == null || CopilotContextPlanner.bytes(arguments) > 8192) throw invalid();
            JsonNode input;
            try { input = reader.readValue(arguments); }
            catch (JsonProcessingException malformed) { throw invalid(); }
            if (input == null || !input.isObject() || !input.path("kind").isTextual() || !input.path("source").isTextual()
                || input.has("version") && !input.path("version").isTextual()) throw invalid();
            var names = input.fieldNames();
            while (names.hasNext()) if (!Set.of("kind", "source", "version", "offset", "limit").contains(names.next())) throw invalid();
            return json.writeValueAsString(Map.of("code", "OK", "page", references.page(session, exercise, input.path("kind").textValue(),
                input.path("source").textValue(), input.path("version").textValue(), integer(input, "offset", 0), integer(input, "limit", 2048))));
        } catch (BusinessException rejected) { return json.writeValueAsString(Map.of("code", rejected.code())); }
    }
    private int integer(JsonNode input, String field, int fallback) {
        if (!input.has(field)) return fallback;
        if (!input.path(field).isIntegralNumber() || !input.path(field).canConvertToInt()) throw invalid();
        return input.path(field).intValue();
    }
    private BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
