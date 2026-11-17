package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;
import com.fasterxml.jackson.databind.JsonNode;

/** One call's cumulative provider counters. Missing counters remain unknown, never estimated. */
public class ProxyUsage {
    private final boolean anthropic;
    private Long input;
    private Long output;
    private Long read;
    private Long write;
    private Long reasoning;
    private int tools;
    private boolean failed;
    public ProxyUsage(boolean anthropic) { this.anthropic = anthropic; }
    public void accept(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (root.has("message")) usage = root.path("message").path("usage");
        if (root.has("response")) usage = root.path("response").path("usage");
        input = maximum(input, number(usage, anthropic || usage.has("input_tokens") ? "input_tokens" : "prompt_tokens"));
        output = maximum(output, number(usage, anthropic || usage.has("output_tokens") ? "output_tokens" : "completion_tokens"));
        if (anthropic) {
            read = maximum(read, number(usage, "cache_read_input_tokens"));
            write = maximum(write, number(usage, "cache_creation_input_tokens"));
        } else {
            JsonNode details = usage.path(usage.has("input_tokens") ? "input_tokens_details" : "prompt_tokens_details");
            read = maximum(read, number(details, "cached_tokens"));
            read = maximum(read, number(usage, "prompt_cache_hit_tokens"));
            reasoning = maximum(reasoning, number(usage.path(usage.has("output_tokens") ? "output_tokens_details" : "completion_tokens_details"), "reasoning_tokens"));
        }
        String type = root.path("type").asText();
        if ("error".equals(type) || "response.failed".equals(type)) failed = true;
        if ("content_block_start".equals(type) && "tool_use".equals(root.path("content_block").path("type").asText())) tools++;
        if ("response.output_item.added".equals(type) && "function_call".equals(root.path("item").path("type").asText())) tools++;
        for (JsonNode block : root.path("content")) if ("tool_use".equals(block.path("type").asText())) tools++;
        for (JsonNode block : root.path("output")) if ("function_call".equals(block.path("type").asText())) tools++;
        for (JsonNode choice : root.path("choices")) {
            tools += choice.path("message").path("tool_calls").size();
            for (JsonNode call : choice.path("delta").path("tool_calls")) if (call.has("id")) tools++;
        }
    }
    public boolean failed() { return failed; }
    public int tools() { return tools; }
    public Long reasoning() { return reasoning; }
    public ModelUsageFactsDTO facts() { return facts(true); }
    public ModelUsageFactsDTO facts(boolean finished) {
        Long totalInput = input;
        Long ordinary = null;
        if (anthropic) {
            if (input != null && read != null && write != null) totalInput = input + read + write;
            else totalInput = null;
            ordinary = input;
        } else if (input != null && read != null) ordinary = input - read;
        boolean valid = ordinary == null || ordinary >= 0;
        boolean complete = finished && valid && totalInput != null && output != null && read != null;
        return new ModelUsageFactsDTO(totalInput, ordinary, read, write, output,
            totalInput == null || output == null ? null : totalInput + output, complete,
            !valid ? "USAGE_INCONSISTENT" : !finished ? "USAGE_PARTIAL" : complete ? null : "USAGE_MISSING");
    }
    private static Long number(JsonNode node, String field) {
        var value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 && value.longValue() <= 1000000000L ? value.longValue() : null;
    }
    private static Long maximum(Long previous, Long next) {
        if (next == null) return previous;
        if (previous == null) return next;
        return Math.max(previous, next);
    }
}
