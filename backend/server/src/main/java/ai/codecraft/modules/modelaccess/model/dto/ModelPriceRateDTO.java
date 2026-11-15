package ai.codecraft.modules.modelaccess.model.dto;

import java.math.BigDecimal;
import java.util.List;

public record ModelPriceRateDTO(List<String> models, BigDecimal inputPerMillion, BigDecimal cacheReadPerMillion, BigDecimal outputPerMillion) {
    public ModelPriceRateDTO { models = List.copyOf(models); }
}
