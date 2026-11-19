package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.ReadToolResultDTO;
import ai.codecraft.modules.workspace.service.ExerciseService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;

/** Reads only the published saved workspace through its existing authorized Go-backed boundary. */
@Service
public class CopilotReadTools {
    private static final int MAX_ARGUMENT_BYTES = 4096;
    private static final int MAX_LINES = 200;
    private static final int MAX_RESULT_BYTES = 64 * 1024;
    private final ExerciseService exercises;
    private final ObjectReader arguments;

    public CopilotReadTools(ExerciseService exercises, ObjectMapper json) {
        this.exercises = exercises;
        arguments = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ReadToolResultDTO execute(String session, String exercise, String name, String input) {
        boolean legacySearch = "grep_code".equals(name);
        name = CopilotToolCatalog.canonical(name == null ? "" : name);
        if (!Set.of("read", "grep", "find").contains(name)) return failure("TOOL_UNSUPPORTED");
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return failure("INVALID_ARGUMENTS");
        try {
            JsonNode value = arguments.readTree(input);
            if (value == null || !value.isObject()) return failure("INVALID_ARGUMENTS");
            var allowed = "read".equals(name) ? Set.of("path", "start_line", "end_line") : "find".equals(name) ? Set.of("pattern") : Set.of("pattern", "path_prefix", "is_regex", "literal", "context_lines", "limit");
            var fields = value.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) return failure("INVALID_ARGUMENTS");
            if (!"read".equals(name)) return search(session, exercise, name, value, legacySearch);
            if (!value.path("path").isTextual()) return failure("INVALID_ARGUMENTS");
            int first = line(value, "start_line", 1);
            int last = line(value, "end_line", first + MAX_LINES - 1);
            if (first < 1 || last < first) return failure("INVALID_ARGUMENTS");
            last = (int) Math.min(last, (long) first + MAX_LINES - 1);
            var snapshot = exercises.find(session, exercise);
            String path = value.path("path").textValue();
            if (!snapshot.paths().contains(path)) return failure("PATH_NOT_ALLOWED");
            var file = exercises.read(session, exercise, path);
            if (!file.revision().equals(snapshot.revision()) || !file.workspaceInstanceId().equals(snapshot.workspaceInstanceId())) return failure("REVISION_CONFLICT");
            String[] lines = file.content().split("\n", -1);
            var result = new ArrayList<String>();
            int bytes = 0;
            boolean truncated = false;
            for (int index = first - 1; index < Math.min(last, lines.length); index++) {
                String row = path + ":" + (index + 1) + ": " + lines[index];
                int size = row.getBytes(StandardCharsets.UTF_8).length + 1;
                if (size > MAX_RESULT_BYTES - 160 - bytes) { truncated = true; break; }
                result.add(row); bytes += size;
            }
            boolean more = truncated || last < lines.length;
            if (truncated && result.isEmpty()) result.add("[Line " + first + " exceeds the byte limit; use read_source with kind FILE and byte pagination]");
            else if (more) result.add("[Showing lines " + first + "-" + (first + result.size() - 1) + " of " + lines.length
                + ". Use start_line=" + (first + result.size()) + " to continue]");
            return new ReadToolResultDTO("OK", file.revision(), result, more, file.contentSha256());
        } catch (IOException malformed) { return failure("INVALID_ARGUMENTS"); }
        catch (BusinessException rejected) { return failure(rejected.errorCode().code()); }
    }

    private ReadToolResultDTO search(String session, String exercise, String name, JsonNode value, boolean legacySearch) {
        if (!value.path("pattern").isTextual()) return failure("INVALID_ARGUMENTS");
        String pattern = value.path("pattern").textValue();
        if (pattern.isEmpty() || pattern.length() > 512) return failure("INVALID_ARGUMENTS");
        boolean glob = "find".equals(name);
        for (String key : List.of("is_regex", "literal")) if (value.has(key) && !value.path(key).isBoolean()) return failure("INVALID_ARGUMENTS");
        if (value.has("path_prefix") && !value.path("path_prefix").isTextual()) return failure("INVALID_ARGUMENTS");
        String prefix = value.path("path_prefix").asText("");
        if (prefix.startsWith("/") || prefix.contains("..") || prefix.contains("\\")) return failure("INVALID_ARGUMENTS");
        int context = line(value, "context_lines", legacySearch ? 0 : 2);
        int limit = line(value, "limit", MAX_LINES);
        if (context < 0 || context > 10 || limit < 1 || limit > MAX_LINES) return failure("INVALID_ARGUMENTS");
        boolean regex = value.path("is_regex").asBoolean(false);
        if (regex && value.path("literal").asBoolean(false)) return failure("INVALID_ARGUMENTS");
        if (glob && (!pattern.matches("[A-Za-z0-9_./*?-]+") || pattern.startsWith("/")
            || List.of(pattern.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals(".")))) return failure("INVALID_ARGUMENTS");
        if (!glob) {
            if (Thread.currentThread().isInterrupted()) return failure("CANCELLED");
            var result = exercises.grep(session, exercise, pattern, prefix, regex, context, limit);
            return new ReadToolResultDTO("OK", result.revision(), result.lines(), result.truncated());
        }
        ExerciseResponse snapshot = exercises.find(session, exercise);
        var result = new ArrayList<String>();
        int bytes = 0;
        var matcher = new AntPathMatcher();
        matcher.setCachePatterns(false);
        for (String path : snapshot.paths().stream().sorted().toList()) {
            if (!path.startsWith(prefix)) continue;
            if (Thread.currentThread().isInterrupted()) return failure("CANCELLED");
            if (!matcher.match(pattern, path)) continue;
            int size = path.getBytes(StandardCharsets.UTF_8).length;
            if (result.size() >= MAX_LINES || size > MAX_RESULT_BYTES - 160 - bytes) return truncated(snapshot.revision(), result);
            result.add(path); bytes += size;
        }
        return new ReadToolResultDTO("OK", snapshot.revision(), result, false);
    }

    private ReadToolResultDTO truncated(String revision, List<String> result) {
        result.add("[Results truncated; narrow pattern or path_prefix to continue]");
        return new ReadToolResultDTO("OK", revision, result, true);
    }

    private int line(JsonNode value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).isIntegralNumber() && value.get(key).canConvertToInt() ? value.get(key).intValue() : -1;
    }

    private ReadToolResultDTO failure(String code) { return new ReadToolResultDTO(code, null, List.of(), false); }
}
