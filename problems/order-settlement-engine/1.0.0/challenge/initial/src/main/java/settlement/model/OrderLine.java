package settlement.model;

public record OrderLine(
        String skuId,
        long unitPriceCents,
        int quantity) {
    public OrderLine {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("unitPriceCents must be non-negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public long grossAmountCents() {
        return Math.multiplyExact(unitPriceCents, (long) quantity);
    }
}
