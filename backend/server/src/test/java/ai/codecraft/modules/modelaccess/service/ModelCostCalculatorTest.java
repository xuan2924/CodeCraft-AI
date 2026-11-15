package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;
import java.time.Instant;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;

class ModelCostCalculatorTest {
    @Test
    void realCheckSampleCanPriceAllNonHitInputWithoutInventingMissingCacheWrites() {
        var usage = OpenAiTokenUsage.builder().inputTokenCount(349).outputTokenCount(62).totalTokenCount(411)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(128).build()).build();
        var facts = new ModelUsageNormalizer().normalize(usage);
        var price = new ModelPriceCatalog().quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT,
            "https://api.deepseek.com", "deepseek-flash", Instant.parse("2026-11-15T12:00:00Z"));
        assertThat(facts.cacheWriteTokens()).isNull();
        assertThat(facts.ordinaryInputTokens()).isNull();
        assertThat(facts.complete()).isFalse();
        assertThat(new ModelCostCalculator().calculate(facts, price).amount()).isEqualByComparingTo("0.000070734");
    }

    @Test
    void deepseekMissIncludesCacheWritesAndTinyKnownCostsDoNotRoundToZero() {
        var price = new ModelPriceCatalog().quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT,
            "https://api.deepseek.com", "deepseek-flash", Instant.parse("2026-11-15T12:00:00Z"));
        var calculator = new ModelCostCalculator();
        var cost = calculator.calculate(new ModelUsageFactsDTO(1000L, 300L, 600L, 100L, 50L, 1050L, true, null), price);
        assertThat(cost.amount()).isEqualByComparingTo("0.0000918");
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(calculator.calculate(new ModelUsageFactsDTO(1L, 0L, 1L, 0L, 0L, 1L, true, null), price).amount())
            .isEqualByComparingTo("0.000000003");
        assertThat(calculator.calculate(new ModelUsageFactsDTO(1000L, null, null, null, 50L, 1050L, false, "USAGE_FIELDS_MISSING"), price).amount()).isNull();
        assertThat(calculator.calculate(null, price).amount()).isNull();
    }
}
