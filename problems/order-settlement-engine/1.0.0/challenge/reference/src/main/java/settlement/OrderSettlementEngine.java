package settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import settlement.idempotency.SettlementLedger;
import settlement.model.CouponSpec;
import settlement.model.OrderLine;
import settlement.model.SettlementReceipt;
import settlement.pricing.DiscountAllocator;

public class OrderSettlementEngine {
    private final DiscountAllocator allocator;
    private final SettlementLedger ledger;

    public OrderSettlementEngine() {
        this(new DiscountAllocator(), new SettlementLedger());
    }

    public OrderSettlementEngine(DiscountAllocator allocator, SettlementLedger ledger) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public SettlementReceipt settle(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(coupon, "coupon");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }

        List<OrderLine> snapshot = List.copyOf(lines);
        Set<String> seenSkus = new HashSet<>(snapshot.size() * 2);
        for (OrderLine line : snapshot) {
            Objects.requireNonNull(line, "line");
            if (!seenSkus.add(line.skuId())) {
                throw new IllegalArgumentException("Duplicate skuId in order: " + line.skuId());
            }
        }

        String fingerprint = canonicalFingerprint(snapshot, coupon);
        return ledger.executeIdempotent(
                idempotencyKey,
                fingerprint,
                () -> allocator.allocate(idempotencyKey, snapshot, coupon));
    }

    public long refundOrderLine(String idempotencyKey, String skuId, int refundQuantity) {
        return ledger.refundLine(idempotencyKey, skuId, refundQuantity);
    }

    private static String canonicalFingerprint(List<OrderLine> lines, CouponSpec coupon) {
        StringBuilder sb = new StringBuilder();
        sb.append(lines.size()).append('|');
        for (OrderLine line : lines) {
            sb.append(line.skuId().length()).append(':').append(line.skuId())
                    .append(',').append(line.unitPriceCents())
                    .append(',').append(line.quantity()).append(';');
        }
        sb.append('|')
                .append(coupon.percentOffBasisPoints()).append(',')
                .append(coupon.thresholdCents()).append(',')
                .append(coupon.thresholdDiscountCents());
        return sb.toString();
    }
}
