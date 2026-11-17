package settlement.model;

import java.util.List;
import java.util.Objects;

public record SettlementReceipt(
        String idempotencyKey,
        long grossTotalCents,
        long percentDiscountCents,
        long thresholdDiscountCents,
        long totalDiscountCents,
        long payableTotalCents,
        List<LineAllocation> lineAllocations) {
    public SettlementReceipt {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(lineAllocations, "lineAllocations");
        lineAllocations = List.copyOf(lineAllocations);
    }

    public record LineAllocation(
            String skuId,
            int settledQuantity,
            long grossCents,
            long percentDiscountCents,
            long thresholdDiscountCents,
            long totalDiscountCents,
            long payableCents) {
        public LineAllocation {
            if (skuId == null || skuId.isBlank()) {
                throw new IllegalArgumentException("skuId must not be blank");
            }
            if (settledQuantity <= 0) {
                throw new IllegalArgumentException("settledQuantity must be positive");
            }
        }
    }
}
