package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.EvaluationReportDTO;
import ai.codecraft.modules.workspace.model.dto.ReportDimensionDTO;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Validates model claims against the immutable evidence directory, never against a model's own citations. */
@Component
public class EvaluationReportValidator {
    public static final Map<String, Integer> WEIGHTS = Map.of("CORRECTNESS", 35, "QUALITY", 25, "VALIDATION", 20, "COLLABORATION", 20);
    private final ObjectMapper json;
    public EvaluationReportValidator(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.json.getFactory().configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
    }
    public EvaluationReportDTO validate(String raw, String verification, List<ReportEvidenceDTO> evidence) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        try {
            JsonNode value = json.readTree(raw);
            fields(value, Set.of("verification", "total", "conclusion", "dimensions"));
            if (!verification.equals(text(value.path("verification"), 32)) || !Set.of("PASSED", "FAILED", "TIMED_OUT", "UNAVAILABLE", "PENDING").contains(verification)) throw invalid();
            String conclusion = text(value.path("conclusion"), 8192);
            Map<String, ReportEvidenceDTO> directory = evidence.stream().collect(Collectors.toMap(ReportEvidenceDTO::id, Function.identity()));
            JsonNode dimensions = value.path("dimensions");
            if (!dimensions.isArray() || dimensions.size() != 4) throw invalid();
            Set<String> seen = new HashSet<>();
            List<ReportDimensionDTO> result = new ArrayList<>();
            int sum = 0;
            boolean complete = true;
            for (JsonNode dimension : dimensions) {
                fields(dimension, Set.of("key", "score", "feedback", "evidenceIds"));
                String key = text(dimension.path("key"), 32);
                if (!WEIGHTS.containsKey(key) || !seen.add(key)) throw invalid();
                Integer score = score(dimension.path("score"), WEIGHTS.get(key));
                String feedback = text(dimension.path("feedback"), 8192);
                JsonNode references = dimension.path("evidenceIds");
                if (!references.isArray() || references.size() > 16) throw invalid();
                List<String> ids = new ArrayList<>();
                boolean objective = false;
                boolean finalTest = false;
                boolean sourceCode = false;
                boolean collaboration = false;
                for (JsonNode reference : references) {
                    String id = text(reference, 4096);
                    var item = directory.get(id);
                    if (item == null || ids.contains(id)) throw invalid();
                    ids.add(id);
                    objective |= Set.of("FILE", "CHANGE", "FINAL_TEST", "COPILOT_MESSAGE", "INTERVIEWER_MESSAGE", "TIMELINE", "CONTEXT_TRACE").contains(item.kind());
                    finalTest |= "FINAL_TEST".equals(item.kind());
                    sourceCode |= "FILE".equals(item.kind()) && ReportEvidenceCollector.sourceFile(item.source());
                    collaboration |= Set.of("COPILOT_MESSAGE", "INTERVIEWER_MESSAGE", "CHANGE", "TIMELINE", "CONTEXT_TRACE").contains(item.kind());
                }
                if (score != null && (!objective
                    || key.equals("CORRECTNESS") && (!finalTest || Set.of("UNAVAILABLE", "PENDING").contains(verification))
                    || key.equals("QUALITY") && !sourceCode || key.equals("COLLABORATION") && !collaboration)) throw invalid();
                if (score == null) complete = false;
                else sum = Math.addExact(sum, score);
                result.add(new ReportDimensionDTO(key, score, feedback, ids));
            }
            Integer total = score(value.path("total"), 100);
            boolean publishTotal = complete && !Set.of("UNAVAILABLE", "PENDING").contains(verification);
            if (publishTotal ? total == null || total != sum : total != null) throw invalid();
            return new EvaluationReportDTO(verification, total, conclusion, result);
        } catch (IOException error) { throw new IllegalArgumentException("Invalid evaluation report", error); }
    }
    private Integer score(JsonNode value, int maximum) {
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > maximum) throw invalid();
        return value.intValue();
    }
    private String text(JsonNode value, int maximum) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid();
        if (value.textValue().codePoints().anyMatch(point -> point == 0 || point >= 0xD800 && point <= 0xDFFF)) throw invalid();
        return value.textValue();
    }
    private void fields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject() || value.size() != expected.size()) throw invalid();
        value.fieldNames().forEachRemaining(field -> { if (!expected.contains(field)) throw invalid(); });
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid evaluation report"); }
}
