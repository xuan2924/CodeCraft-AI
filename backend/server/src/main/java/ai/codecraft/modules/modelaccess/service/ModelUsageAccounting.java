package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelPriceSnapshotDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.model.output.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded numeric metadata only. Does not perform I/O, authorize calls, or replace original SDK evidence. */
@Component
public class ModelUsageAccounting {
    private final ModelUsageNormalizer normalizer;
    private final ModelPriceCatalog prices;
    private final ModelCostCalculator costs;
    private final ObjectMapper json = JsonMapper.builder().visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY).build();

    public ModelUsageAccounting(ModelUsageNormalizer normalizer, ModelPriceCatalog prices, ModelCostCalculator costs) {
        this.normalizer = normalizer; this.prices = prices; this.costs = costs;
    }

    public String configuration(ModelInvocationDTO target) { return encode(Map.of("connection", target.connection(), "model", target.model())); }

    public String price(ModelInvocationDTO target, Instant at) {
        var connection = target.connection();
        return encode(prices.quote(connection.preset(), connection.protocol(), connection.baseUrl(), target.model().modelId(), at));
    }

    public ModelUsageReceiptDTO receipt(TokenUsage usage, String price) { return receipt(normalizer.normalize(usage), price); }

    public ModelUsageReceiptDTO receipt(ModelUsageFactsDTO facts, String price) {
        ModelPriceSnapshotDTO snapshot = null;
        if (price != null) {
            try { snapshot = json.readValue(price, ModelPriceSnapshotDTO.class); }
            catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid persisted price snapshot", failure); }
        }
        var cost = costs.calculate(facts, snapshot);
        return new ModelUsageReceiptDTO(encode(facts), cost.currency(), cost.amount(), cost.reason());
    }

    public ModelUsageFactsDTO facts(TokenUsage usage) { return normalizer.normalize(usage); }
    public String raw(TokenUsage usage) { return usage == null ? null : encode(usage); }

    private String encode(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 32768) throw new IllegalStateException("Model usage metadata exceeds its bound");
            return encoded;
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode model usage metadata", failure); }
    }
}
