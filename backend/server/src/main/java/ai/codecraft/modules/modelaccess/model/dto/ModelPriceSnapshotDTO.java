package ai.codecraft.modules.modelaccess.model.dto;

import java.math.BigDecimal;

/** Immutable quote identity and rates, not a supplier invoice. Unknown quotes have a reason and no rates. */
public record ModelPriceSnapshotDTO(String revision, String currency, BigDecimal inputPerMillion,
    BigDecimal cacheReadPerMillion, BigDecimal outputPerMillion, String band, String source,
    String verifiedAt, String validFrom, String validUntil, String reason) { }
