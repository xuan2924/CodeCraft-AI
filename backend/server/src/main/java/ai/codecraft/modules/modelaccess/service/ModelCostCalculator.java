package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelCostDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelPriceSnapshotDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class ModelCostCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1000000);

    public ModelCostDTO calculate(ModelUsageFactsDTO facts, ModelPriceSnapshotDTO price) {
        if (price == null || price.reason() != null) return new ModelCostDTO(null, null, price == null ? "PRICE_UNKNOWN" : price.reason());
        if (facts == null || facts.inputTokens() == null || facts.outputTokens() == null || facts.cacheReadTokens() == null
            || facts.inputTokens() < 0 || facts.outputTokens() < 0 || facts.cacheReadTokens() < 0
            || facts.cacheReadTokens() > facts.inputTokens() || "USAGE_INCONSISTENT".equals(facts.reason()))
            return new ModelCostDTO(price.currency(), null, "PRICE_USAGE_MISSING");
        // This reviewed DeepSeek tariff charges all non-hit input once, including any cache creation.
        var input = BigDecimal.valueOf(facts.inputTokens() - facts.cacheReadTokens()).multiply(price.inputPerMillion());
        var read = BigDecimal.valueOf(facts.cacheReadTokens()).multiply(price.cacheReadPerMillion());
        var output = BigDecimal.valueOf(facts.outputTokens()).multiply(price.outputPerMillion());
        return new ModelCostDTO(price.currency(), input.add(read).add(output).divide(MILLION), null);
    }
}
