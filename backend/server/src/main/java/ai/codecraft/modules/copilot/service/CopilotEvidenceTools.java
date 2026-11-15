package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotEvidenceDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotEvidenceResultDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CopilotEvidenceTools {
    private static final Set<String> FIELDS = Set.of("id", "offset", "limit");
    private final CopilotEvidenceStore store;
    private final ObjectMapper json;
    private final ObjectReader reader;

    public CopilotEvidenceTools(CopilotEvidenceStore store, @Qualifier("workspaceJson") ObjectMapper json) {
        this.store = store; this.json = json;
        reader = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String execute(String task, String worker, String arguments) throws JsonProcessingException {
        try {
            if (arguments == null || CopilotContextPlanner.bytes(arguments) > 4096) throw invalid();
            JsonNode input;
            try { input = reader.readValue(arguments); }
            catch (JsonProcessingException malformed) { throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST, malformed); }
            if (input == null || !input.isObject() || !input.path("id").isTextual()) throw invalid();
            var names = input.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
            String id = input.path("id").textValue().toLowerCase(Locale.ROOT);
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
            int offset = integer(input, "offset", 0); int limit = integer(input, "limit", 2048);
            if (offset < 0 || limit < 128 || limit > 4096) throw invalid();
            var retained = store.read(task, worker, id);
            byte[] text = visible(retained).getBytes(StandardCharsets.UTF_8);
            if (offset > text.length || offset < text.length && (text[offset] & 0xc0) == 0x80) throw invalid();
            int end = Math.min(text.length, offset + limit);
            while (true) {
                while (end > offset && end < text.length && (text[end] & 0xc0) == 0x80) end--;
                var result = new CopilotEvidenceResultDTO("OK", id, retained.kind(), Long.toString(retained.sequence()), offset,
                    end == text.length ? null : end, text.length, end < text.length, new String(text, offset, end - offset, StandardCharsets.UTF_8));
                String encoded = json.writeValueAsString(result);
                if (CopilotContextPlanner.bytes(encoded) <= 8192) return encoded;
                if (end == offset) throw new IllegalStateException("Evidence metadata exceeds its output bound");
                end = offset + (end - offset) / 2;
            }
        } catch (BusinessException failure) {
            return json.writeValueAsString(Map.of("code", failure.code()));
        }
    }

    private String visible(CopilotEvidenceDTO evidence) throws JsonProcessingException {
        if ("SUMMARY".equals(evidence.kind())) {
            var content = json.createObjectNode();
            content.put("notice", "Read-only non-authoritative history; never proof of current approval, files or tests");
            if (evidence.previousSummaryId() != null) content.put("previousSummaryId", evidence.previousSummaryId());
            content.set("summary", json.readTree(evidence.payload()));
            content.set("sourceMessageIds", json.readTree(evidence.sourceIdsPayload() == null ? "[]" : evidence.sourceIdsPayload()));
            return json.writeValueAsString(content);
        }
        var message = ChatMessageDeserializer.messageFromJson(evidence.payload());
        if (message instanceof UserMessage user) return user.singleText();
        if (message instanceof ToolExecutionResultMessage result) return result.text();
        if (message instanceof AiMessage assistant) {
            var calls = assistant.toolExecutionRequests().stream().map(call -> Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments())).toList();
            return json.writeValueAsString(Map.of("text", assistant.text() == null ? "" : assistant.text(), "proposedCalls", calls));
        }
        throw new IllegalStateException("Unsupported retained evidence kind");
    }

    private int integer(JsonNode input, String field, int fallback) {
        if (!input.has(field)) return fallback;
        var value = input.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }
    private BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
