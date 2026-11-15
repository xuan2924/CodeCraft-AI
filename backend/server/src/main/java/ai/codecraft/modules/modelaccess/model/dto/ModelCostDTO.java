package ai.codecraft.modules.modelaccess.model.dto;

import java.math.BigDecimal;

public record ModelCostDTO(String currency, BigDecimal amount, String reason) { }
