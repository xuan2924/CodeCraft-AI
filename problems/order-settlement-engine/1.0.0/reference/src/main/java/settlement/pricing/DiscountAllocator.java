package settlement.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import settlement.model.CouponSpec;
import settlement.model.OrderLine;
import settlement.model.SettlementReceipt;

public class DiscountAllocator {
    private static final BigInteger BASIS_POINTS_DENOMINATOR = BigInteger.valueOf(10_000L);

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

        long percentDiscountCents = BigInteger.valueOf(grossTotalCents)
                .multiply(BigInteger.valueOf(coupon.percentOffBasisPoints()))
                .divide(BASIS_POINTS_DENOMINATOR)
                .longValueExact();
        long[] percentAlloc = allocateProportionally(percentDiscountCents, lineGross, grossTotalCents);

        long afterPercentSubtotal = grossTotalCents - percentDiscountCents;
        long[] lineAfterPercent = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            lineAfterPercent[i] = lineGross[i] - percentAlloc[i];
        }

        long thresholdDiscountCents = (coupon.thresholdCents() > 0 && afterPercentSubtotal >= coupon.thresholdCents())
                ? Math.min(coupon.thresholdDiscountCents(), afterPercentSubtotal)
                : 0L;
        long[] thresholdAlloc = allocateProportionally(thresholdDiscountCents, lineAfterPercent, afterPercentSubtotal);

        List<SettlementReceipt.LineAllocation> allocations = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            long lineTotalDiscount = Math.addExact(percentAlloc[i], thresholdAlloc[i]);
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

    private static long[] allocateProportionally(long totalDiscount, long[] weights, long totalWeight) {
        long[] allocated = new long[weights.length];
        if (totalDiscount == 0 || totalWeight == 0) {
            return allocated;
        }

        BigInteger discountBig = BigInteger.valueOf(totalDiscount);
        BigInteger totalWeightBig = BigInteger.valueOf(totalWeight);
        BigInteger[] remainders = new BigInteger[weights.length];
        long allocatedSum = 0L;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] divRem = discountBig.multiply(BigInteger.valueOf(weights[i]))
                    .divideAndRemainder(totalWeightBig);
            allocated[i] = divRem[0].longValueExact();
            remainders[i] = divRem[1];
            allocatedSum += allocated[i];
        }

        long leftover = totalDiscount - allocatedSum;
        if (leftover > 0) {
            List<Integer> order = new ArrayList<>(weights.length);
            for (int i = 0; i < weights.length; i++) {
                order.add(i);
            }
            order.sort(Comparator
                    .<Integer, BigInteger>comparing(i -> remainders[i], Comparator.reverseOrder())
                    .thenComparing(i -> weights[i], Comparator.reverseOrder())
                    .thenComparingInt(Integer::intValue));
            for (int i = 0; i < leftover; i++) {
                allocated[order.get(i)] += 1L;
            }
        }
        return allocated;
    }
}
