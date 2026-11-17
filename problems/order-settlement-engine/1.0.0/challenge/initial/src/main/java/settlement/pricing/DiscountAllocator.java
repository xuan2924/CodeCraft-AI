package settlement.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import settlement.model.CouponSpec;
import settlement.model.OrderLine;
import settlement.model.SettlementReceipt;

public class DiscountAllocator {
    public SettlementReceipt allocate(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(coupon, "coupon");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }

        long[] lineGross = new long[lines.size()];
        long grossTotalCents = 0L;
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = Objects.requireNonNull(lines.get(i), "line");
            lineGross[i] = line.grossAmountCents();
            grossTotalCents = Math.addExact(grossTotalCents, lineGross[i]);
        }

        // Bug 1: Evaluates threshold discount against grossTotalCents (wrong discount order).
        long thresholdDiscountCents = (coupon.thresholdCents() > 0 && grossTotalCents >= coupon.thresholdCents())
                ? Math.min(coupon.thresholdDiscountCents(), grossTotalCents)
                : 0L;
        long afterThreshold = grossTotalCents - thresholdDiscountCents;

        // Bug 2: Uses double floating-point arithmetic, losing precision above 2^53 or on large totals.
        long percentDiscountCents = (long) Math.floor(afterThreshold * (coupon.percentOffBasisPoints() / 10000.0));

        long[] thresholdAlloc = allocateNaive(thresholdDiscountCents, lineGross, grossTotalCents);
        long[] lineAfterThreshold = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            lineAfterThreshold[i] = lineGross[i] - thresholdAlloc[i];
        }
        long[] percentAlloc = allocateNaive(percentDiscountCents, lineAfterThreshold, afterThreshold);

        List<SettlementReceipt.LineAllocation> allocations = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            long lineTotalDiscount = percentAlloc[i] + thresholdAlloc[i];
            long linePayable = lineGross[i] - lineTotalDiscount;
            allocations.add(new SettlementReceipt.LineAllocation(
                    line.skuId(),
                    line.quantity(),
                    lineGross[i],
                    percentAlloc[i],
                    thresholdAlloc[i],
                    lineTotalDiscount,
                    linePayable));
        }

        long totalDiscountCents = Math.addExact(percentDiscountCents, thresholdDiscountCents);
        long payableTotalCents = grossTotalCents - totalDiscountCents;
        return new SettlementReceipt(
                idempotencyKey,
                grossTotalCents,
                percentDiscountCents,
                thresholdDiscountCents,
                totalDiscountCents,
                payableTotalCents,
                allocations);
    }

    // Bug 3: Truncates fractional cents without distributing tail cents (non-conservation).
    private long[] allocateNaive(long totalDiscount, long[] weights, long totalWeight) {
        long[] allocated = new long[weights.length];
        if (totalDiscount == 0 || totalWeight == 0) {
            return allocated;
        }
        for (int i = 0; i < weights.length; i++) {
            double share = (double) totalDiscount * ((double) weights[i] / (double) totalWeight);
            allocated[i] = (long) Math.floor(share);
        }
        return allocated;
    }
}
