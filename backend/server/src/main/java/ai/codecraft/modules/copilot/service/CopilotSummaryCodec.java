package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.langchain4j.data.message.AiMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Validates a candidate only. It cannot publish a context or change an approval/test outcome. */
@Component
public class CopilotSummaryCodec {
    private static final Set<String> FIELDS = Set.of("goal", "decisions", "changes", "state", "evidence");
    private final ObjectMapper json;
    private final ObjectReader reader;

    public CopilotSummaryCodec(@Qualifier("workspaceJson") ObjectMapper json) {
        this.json = json;
        reader = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String prepare(AiMessage response, int maximumBytes, List<String> sourceIds, String previousSummaryId) {
        if (response == null || response.hasToolExecutionRequests() || response.text() == null
            || CopilotContextPlanner.bytes(response.text()) > maximumBytes * 2L + 64) throw invalid();
        String text = response.text().trim();
        if (text.startsWith("```json\n") && text.endsWith("\n```")) text = text.substring(8, text.length() - 4).trim();
        try {
            JsonNode root = reader.readValue(text);
            if (root == null || !root.isObject()) throw invalid();
            var fields = new HashSet<String>(); root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(FIELDS)) throw invalid();
            string(root.get("goal")); string(root.get("state"));
            strings(root.get("decisions"), 32); strings(root.get("changes"), 32); strings(root.get("evidence"), 16);
            var allowed = new HashSet<>(sourceIds);
            if (previousSummaryId != null) allowed.add(previousSummaryId);
            var used = new HashSet<String>();
            for (JsonNode reference : root.get("evidence")) {
                if (!allowed.contains(reference.textValue()) || !used.add(reference.textValue())) throw invalid();
            }
            if (used.isEmpty()) throw invalid();
            String prepared = json.writeValueAsString(root);
            if (CopilotContextPlanner.bytes(prepared) > maximumBytes) throw invalid();
            return prepared;
        } catch (JsonProcessingException malformed) {
            throw new BusinessException(CopilotErrorCode.COPILOT_SUMMARY_INVALID, malformed);
        }
    }

    private void strings(JsonNode node, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) throw invalid();
        for (JsonNode entry : node) string(entry);
    }

    private void string(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        if (value.isBlank() || value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
            || CopilotContextPlanner.bytes(value) > 2048) throw invalid();
    }

    private BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_SUMMARY_INVALID); }
}
