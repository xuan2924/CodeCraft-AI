package settlement.model;

public record CouponSpec(
        int percentOffBasisPoints,
        long thresholdCents,
        long thresholdDiscountCents) {
    public CouponSpec {
        if (percentOffBasisPoints < 0 || percentOffBasisPoints > 10_000) {
            throw new IllegalArgumentException("percentOffBasisPoints must be between 0 and 10000");
        }
        if (thresholdCents < 0 || thresholdDiscountCents < 0) {
            throw new IllegalArgumentException("threshold values must be non-negative");
        }
        if (thresholdCents == 0 && thresholdDiscountCents > 0) {
            throw new IllegalArgumentException("thresholdDiscountCents requires positive thresholdCents");
        }
        if (thresholdDiscountCents > thresholdCents) {
            throw new IllegalArgumentException("thresholdDiscountCents cannot exceed thresholdCents");
        }
    }

    public static CouponSpec none() {
        return new CouponSpec(0, 0L, 0L);
    }
}
