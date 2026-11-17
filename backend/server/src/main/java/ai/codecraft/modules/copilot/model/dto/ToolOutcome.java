package ai.codecraft.modules.copilot.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Lifecycle completion is independent of whether the requested business operation succeeded. */
public sealed interface ToolOutcome permits ToolOutcome.Suspended, ToolOutcome.Completed {
    enum Code { OK, INVALID_ARGUMENTS, CONFLICT, DENIED, APPROVAL_REJECTED, LEGACY_UNCLASSIFIED }
    record Suspended() implements ToolOutcome {}
    record Completed(Code outcomeCode, String payload) implements ToolOutcome {
        public Completed {
            if (outcomeCode == null || payload == null) throw new IllegalArgumentException("Completed tool requires an outcome and payload");
        }
        @Override public String toString() { return "Completed[outcomeCode=" + outcomeCode + ", payload=redacted]"; }
    }
    default boolean waiting() { return this instanceof Suspended; }
    default String body() { return this instanceof Completed completed ? completed.payload() : null; }
    static Code classify(JsonNode value) {
        if (value == null || !value.isObject()) return Code.LEGACY_UNCLASSIFIED;
        if (value.has("childTaskId") && value.has("state")) return Code.OK;
        String code = value.path("code").asText("");
        if (Set.of("OK", "APPLIED", "TEST_RESULT", "COMMAND_RESULT", "DELEGATION_RESULT", "SKILL_LOADED").contains(code)) return Code.OK;
        if (code.contains("CONFLICT") || "COMMAND_SYNC_FAILED".equals(code) && "CONFLICT".equals(value.path("result").path("synchronization").asText())) return Code.CONFLICT;
        if (code.contains("REJECTED") || "APPROVAL_REJECTED".equals(code)) return Code.APPROVAL_REJECTED;
        if (code.contains("INVALID") || code.contains("NOT_FOUND") || code.contains("FILE_EXISTS") || code.contains("EDIT_")) return Code.INVALID_ARGUMENTS;
        return Code.DENIED;
    }
}
