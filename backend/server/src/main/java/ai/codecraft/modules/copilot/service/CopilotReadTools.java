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
        if (!Set.of("view_file", "grep_code", "glob_files").contains(name == null ? "" : name)) return failure("TOOL_UNSUPPORTED");
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) return failure("INVALID_ARGUMENTS");
        try {
            JsonNode value = arguments.readTree(input);
            if (value == null || !value.isObject()) return failure("INVALID_ARGUMENTS");
            var allowed = "view_file".equals(name) ? Set.of("path", "start_line", "end_line") : Set.of("pattern");
            var fields = value.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) return failure("INVALID_ARGUMENTS");
            if (!"view_file".equals(name)) return search(session, exercise, name, value);
            if (!value.path("path").isTextual()) return failure("INVALID_ARGUMENTS");
            int first = line(value, "start_line", 1);
            int last = line(value, "end_line", first + MAX_LINES - 1);
            if (first < 1 || last < first || last - first >= MAX_LINES) return failure("INVALID_ARGUMENTS");
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
                int size = row.getBytes(StandardCharsets.UTF_8).length;
                if (size > MAX_RESULT_BYTES - bytes) { truncated = true; break; }
                result.add(row); bytes += size;
            }
            return new ReadToolResultDTO("OK", file.revision(), result, truncated || last < lines.length);
        } catch (IOException malformed) { return failure("INVALID_ARGUMENTS"); }
        catch (BusinessException rejected) { return failure(rejected.errorCode().code()); }
    }

    private ReadToolResultDTO search(String session, String exercise, String name, JsonNode value) {
        if (!value.path("pattern").isTextual()) return failure("INVALID_ARGUMENTS");
        String pattern = value.path("pattern").textValue();
        if (pattern.isEmpty() || pattern.length() > 512) return failure("INVALID_ARGUMENTS");
        boolean glob = "glob_files".equals(name);
        if (glob && (!pattern.matches("[A-Za-z0-9_./*?-]+") || pattern.startsWith("/")
            || List.of(pattern.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals(".")))) return failure("INVALID_ARGUMENTS");
        ExerciseResponse snapshot = exercises.find(session, exercise);
        var result = new ArrayList<String>();
        int bytes = 0;
        var matcher = new AntPathMatcher();
        matcher.setCachePatterns(false);
        for (String path : snapshot.paths().stream().sorted().toList()) {
            if (Thread.currentThread().isInterrupted()) return failure("CANCELLED");
            if (glob) {
                if (!matcher.match(pattern, path)) continue;
                int size = path.getBytes(StandardCharsets.UTF_8).length;
                if (result.size() >= MAX_LINES || size > MAX_RESULT_BYTES - bytes) return new ReadToolResultDTO("OK", snapshot.revision(), result, true);
                result.add(path); bytes += size;
            } else {
                var file = exercises.read(session, exercise, path);
                if (!file.revision().equals(snapshot.revision()) || !file.workspaceInstanceId().equals(snapshot.workspaceInstanceId())) return failure("REVISION_CONFLICT");
                String[] lines = file.content().split("\\n", -1);
                for (int index = 0; index < lines.length; index++) {
                    if (!lines[index].contains(pattern)) continue;
                    String row = path + ":" + (index + 1) + ": " + lines[index];
                    int size = row.getBytes(StandardCharsets.UTF_8).length;
                    if (result.size() >= MAX_LINES || size > MAX_RESULT_BYTES - bytes) return new ReadToolResultDTO("OK", snapshot.revision(), result, true);
                    result.add(row); bytes += size;
                }
            }
        }
        return new ReadToolResultDTO("OK", snapshot.revision(), result, false);
    }

    private int line(JsonNode value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        return value.get(key).isIntegralNumber() && value.get(key).canConvertToInt() ? value.get(key).intValue() : -1;
    }

    private ReadToolResultDTO failure(String code) { return new ReadToolResultDTO(code, null, List.of(), false); }
}
