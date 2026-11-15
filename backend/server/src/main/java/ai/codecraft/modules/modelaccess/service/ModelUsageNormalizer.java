package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.springframework.stereotype.Component;

@Component
public class ModelUsageNormalizer {
    public ModelUsageFactsDTO normalize(TokenUsage usage) {
        if (usage instanceof AnthropicTokenUsage anthropic) {
            Long ordinary = counter(usage.inputTokenCount());
            Long read = counter(anthropic.cacheReadInputTokens());
            Long write = counter(anthropic.cacheCreationInputTokens());
            Long output = counter(usage.outputTokenCount());
            Long input = ordinary == null || read == null || write == null ? null : ordinary + read + write;
            Long total = input == null || output == null ? null : input + output;
            return new ModelUsageFactsDTO(input, ordinary, read, write, output, total, total != null, total == null ? "USAGE_MISSING" : null);
        }
        if (usage == null) return new ModelUsageFactsDTO(null, null, null, null, null, null, false, "USAGE_MISSING");
        Long input = counter(usage.inputTokenCount());
        Long output = counter(usage.outputTokenCount());
        Long total = counter(usage.totalTokenCount());
        Long read = null; Long write = null;
        if (usage instanceof OpenAiTokenUsage openai && openai.inputTokensDetails() != null) {
            read = counter(openai.inputTokensDetails().cachedTokens());
            write = counter(openai.inputTokensDetails().cacheWriteTokens());
        }
        boolean inconsistent = total != null && input != null && output != null && total.longValue() != input + output;
        inconsistent |= input != null && ((read != null && read > input) || (write != null && write > input)
            || (read != null && write != null && read + write > input));
        Long ordinary = !inconsistent && input != null && read != null && write != null ? input - read - write : null;
        if (inconsistent) total = null;
        boolean complete = !inconsistent && ordinary != null && output != null && total != null;
        return new ModelUsageFactsDTO(input, ordinary, read, write, output, total, complete,
            inconsistent ? "USAGE_INCONSISTENT" : complete ? null : "USAGE_FIELDS_MISSING");
    }

    private Long counter(Integer value) { return value == null || value < 0 ? null : value.longValue(); }
}
