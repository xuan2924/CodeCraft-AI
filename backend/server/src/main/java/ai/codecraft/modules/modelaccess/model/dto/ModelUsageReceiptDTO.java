package ai.codecraft.modules.modelaccess.model.dto;

import java.math.BigDecimal;

public record ModelUsageReceiptDTO(String factsPayload, String currency, BigDecimal amount, String priceReason) { }
