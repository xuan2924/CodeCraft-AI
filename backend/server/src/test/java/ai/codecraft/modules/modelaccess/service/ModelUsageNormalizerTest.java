package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

class ModelUsageNormalizerTest {
    @Test
    void openAiCacheCountersAreSubsetsOfTheReportedInputNotAdditionalTokens() {
        var usage = OpenAiTokenUsage.builder().inputTokenCount(1000).outputTokenCount(50).totalTokenCount(1050)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(600).cacheWriteTokens(100).build()).build();
        var facts = new ModelUsageNormalizer().normalize(usage);
        assertThat(facts.inputTokens()).isEqualTo(1000L);
        assertThat(facts.ordinaryInputTokens()).isEqualTo(300L);
        assertThat(facts.cacheReadTokens()).isEqualTo(600L);
        assertThat(facts.cacheWriteTokens()).isEqualTo(100L);
        assertThat(facts.totalTokens()).isEqualTo(1050L);
        assertThat(facts.complete()).isTrue();
    }

    @Test
    void missingCacheFieldsAreNotZerosAndInconsistentTotalsAreNotPublishedAsComplete() {
        var normalizer = new ModelUsageNormalizer();
        var missing = normalizer.normalize(new TokenUsage(100, 20, 120));
        assertThat(missing.inputTokens()).isEqualTo(100L);
        assertThat(missing.totalTokens()).isEqualTo(120L);
        assertThat(missing.cacheReadTokens()).isNull();
        assertThat(missing.cacheWriteTokens()).isNull();
        assertThat(missing.complete()).isFalse();
        assertThat(normalizer.normalize(new TokenUsage(100, 20, 999)).totalTokens()).isNull();
        assertThat(normalizer.normalize(null).inputTokens()).isNull();
        var contradictory = OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(20).totalTokenCount(120)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(80).cacheWriteTokens(30).build()).build();
        assertThat(normalizer.normalize(contradictory).reason()).isEqualTo("USAGE_INCONSISTENT");
        assertThat(normalizer.normalize(contradictory).ordinaryInputTokens()).isNull();
        assertThat(normalizer.normalize(contradictory).totalTokens()).isNull();
        assertThat(normalizer.normalize(AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(20).build()).inputTokens()).isNull();
    }

    @Test
    void anthropicCacheReadsAndWritesOccupyTheWindowWithoutBeingCountedTwice() {
        var usage = AnthropicTokenUsage.builder().inputTokenCount(100).cacheReadInputTokens(1000)
            .cacheCreationInputTokens(20).outputTokenCount(10).build();
        var facts = new ModelUsageNormalizer().normalize(usage);
        assertThat(facts.inputTokens()).isEqualTo(1120L);
        assertThat(facts.ordinaryInputTokens()).isEqualTo(100L);
        assertThat(facts.cacheReadTokens()).isEqualTo(1000L);
        assertThat(facts.cacheWriteTokens()).isEqualTo(20L);
        assertThat(facts.outputTokens()).isEqualTo(10L);
        assertThat(facts.totalTokens()).isEqualTo(1130L);
        assertThat(facts.complete()).isTrue();
    }
}
