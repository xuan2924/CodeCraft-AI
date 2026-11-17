package settlement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import settlement.idempotency.SettlementLedger;
import settlement.model.CouponSpec;
import settlement.model.OrderLine;
import settlement.model.SettlementReceipt;
import settlement.pricing.DiscountAllocator;

class OrderSettlementEngineTest {

    @Test
    void settlesSingleLineWithoutCoupon() {
        OrderSettlementEngine engine = new OrderSettlementEngine();
        SettlementReceipt receipt = engine.settle(
                "order-single-1",
                List.of(new OrderLine("SKU-A", 1500L, 2)),
                CouponSpec.none());

        assertEquals("order-single-1", receipt.idempotencyKey());
        assertEquals(3000L, receipt.grossTotalCents());
        assertEquals(0L, receipt.percentDiscountCents());
        assertEquals(0L, receipt.thresholdDiscountCents());
        assertEquals(0L, receipt.totalDiscountCents());
        assertEquals(3000L, receipt.payableTotalCents());
        assertEquals(1, receipt.lineAllocations().size());
        assertEquals(new SettlementReceipt.LineAllocation("SKU-A", 2, 3000L, 0L, 0L, 0L, 3000L),
                receipt.lineAllocations().get(0));
    }

    @Test
    void allocatesPercentageAndThresholdWithLargestRemainderAndConservesCents() {
        OrderSettlementEngine engine = new OrderSettlementEngine();

        SettlementReceipt equalTieReceipt = engine.settle(
                "order-equal-3",
                List.of(
                        new OrderLine("SKU-A", 1000L, 1),
                        new OrderLine("SKU-B", 1000L, 1),
                        new OrderLine("SKU-C", 1000L, 1)),
                new CouponSpec(0, 2000L, 100L));

        assertEquals(100L, equalTieReceipt.thresholdDiscountCents());
        assertEquals(2900L, equalTieReceipt.payableTotalCents());
        assertEquals(List.of(34L, 33L, 33L),
                equalTieReceipt.lineAllocations().stream().map(SettlementReceipt.LineAllocation::thresholdDiscountCents).toList());
        assertEquals(List.of(966L, 967L, 967L),
                equalTieReceipt.lineAllocations().stream().map(SettlementReceipt.LineAllocation::payableCents).toList());

        SettlementReceipt unevenReceipt = engine.settle(
                "order-uneven-2",
                List.of(
                        new OrderLine("SKU-1", 100L, 1),
                        new OrderLine("SKU-2", 200L, 1)),
                new CouponSpec(0, 300L, 100L));

        assertEquals(List.of(33L, 67L),
                unevenReceipt.lineAllocations().stream().map(SettlementReceipt.LineAllocation::thresholdDiscountCents).toList());
        assertEquals(200L, unevenReceipt.lineAllocations().stream()
                .mapToLong(SettlementReceipt.LineAllocation::payableCents).sum());
    }

    @Test
    void appliesPercentageBeforeThresholdAndChecksThresholdOnPostPercentSubtotal() {
        OrderSettlementEngine engine = new OrderSettlementEngine();

        // 10000 gross -> 15% off (1500) -> post-percent subtotal = 8500 < 9000 threshold -> threshold must NOT apply.
        SettlementReceipt missedThreshold = engine.settle(
                "order-missed-threshold",
                List.of(
                        new OrderLine("SKU-A", 5000L, 1),
                        new OrderLine("SKU-B", 5000L, 1)),
                new CouponSpec(1500, 9000L, 1000L));

        assertEquals(1500L, missedThreshold.percentDiscountCents());
        assertEquals(0L, missedThreshold.thresholdDiscountCents());
        assertEquals(8500L, missedThreshold.payableTotalCents());

        // 10000 gross -> 10% off (1000) -> post-percent subtotal = 9000 >= 9000 threshold -> threshold 500 applies.
        SettlementReceipt hitThreshold = engine.settle(
                "order-hit-threshold",
                List.of(
                        new OrderLine("SKU-A", 6000L, 1),
                        new OrderLine("SKU-B", 4000L, 1)),
                new CouponSpec(1000, 9000L, 500L));

        assertEquals(1000L, hitThreshold.percentDiscountCents());
        assertEquals(500L, hitThreshold.thresholdDiscountCents());
        assertEquals(8500L, hitThreshold.payableTotalCents());
        assertEquals(5100L, hitThreshold.lineAllocations().get(0).payableCents());
        assertEquals(3400L, hitThreshold.lineAllocations().get(1).payableCents());
    }

    @Test
    void zeroPriceLineNeverGetsDiscountOrNegativePayable() {
        OrderSettlementEngine engine = new OrderSettlementEngine();
        SettlementReceipt receipt = engine.settle(
                "order-free-gift",
                List.of(
                        new OrderLine("FREE-GIFT", 0L, 1),
                        new OrderLine("ITEM-A", 100L, 1),
                        new OrderLine("ITEM-B", 200L, 1)),
                new CouponSpec(0, 300L, 100L));

        assertEquals(0L, receipt.lineAllocations().get(0).totalDiscountCents());
        assertEquals(0L, receipt.lineAllocations().get(0).payableCents());
        assertEquals(33L, receipt.lineAllocations().get(1).thresholdDiscountCents());
        assertEquals(67L, receipt.lineAllocations().get(2).thresholdDiscountCents());
        assertEquals(200L, receipt.payableTotalCents());
    }

    @Test
    void handlesLargeAmountsWithoutLongOrDoublePrecisionLoss() {
        OrderSettlementEngine engine = new OrderSettlementEngine();

        // Both values exceed IEEE-754 double 53-bit exact integer range (2^53 = 9007199254740992).
        SettlementReceipt aboveDoublePrecision = engine.settle(
                "order-high-precision",
                List.of(
                        new OrderLine("HIGH-1", 9_007_199_254_740_993L, 1),
                        new OrderLine("HIGH-2", 9_007_199_254_740_995L, 1)),
                new CouponSpec(1000, 0L, 0L));

        assertEquals(1_801_439_850_948_198L, aboveDoublePrecision.percentDiscountCents());
        assertEquals(900_719_925_474_099L, aboveDoublePrecision.lineAllocations().get(0).percentDiscountCents());
        assertEquals(900_719_925_474_099L, aboveDoublePrecision.lineAllocations().get(1).percentDiscountCents());
        assertEquals(16_212_958_658_533_790L, aboveDoublePrecision.payableTotalCents());

        SettlementReceipt halfMax = engine.settle(
                "order-half-max",
                List.of(new OrderLine("HUGE", Long.MAX_VALUE / 2, 1)),
                new CouponSpec(2500, 0L, 0L));

        assertEquals(1_152_921_504_606_846_975L, halfMax.percentDiscountCents());
        assertEquals(3_458_764_513_820_540_928L, halfMax.payableTotalCents());
    }

    @Test
    void rejectsInvalidOrderInputsAndDetectsTotalOverflow() {
        OrderSettlementEngine engine = new OrderSettlementEngine();
        List<OrderLine> validLines = List.of(new OrderLine("SKU-1", 100L, 1));

        assertThrows(IllegalArgumentException.class, () -> engine.settle(" ", validLines, CouponSpec.none()));
        assertThrows(IllegalArgumentException.class, () -> engine.settle(null, validLines, CouponSpec.none()));
        assertThrows(NullPointerException.class, () -> engine.settle("k-null-lines", null, CouponSpec.none()));
        assertThrows(IllegalArgumentException.class, () -> engine.settle("k-empty", List.of(), CouponSpec.none()));
        assertThrows(NullPointerException.class,
                () -> engine.settle("k-null-elem", Arrays.asList((OrderLine) null), CouponSpec.none()));
        assertThrows(NullPointerException.class, () -> engine.settle("k-null-coupon", validLines, null));
        assertThrows(IllegalArgumentException.class, () -> engine.settle(
                "k-dup-sku",
                List.of(new OrderLine("DUP", 100L, 1), new OrderLine("DUP", 200L, 1)),
                CouponSpec.none()));
        assertThrows(IllegalArgumentException.class, () -> new OrderLine("SKU", -1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderLine("SKU", 100L, 0));
        assertThrows(IllegalArgumentException.class, () -> new CouponSpec(10_001, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CouponSpec(0, 100L, 101L));
        assertThrows(ArithmeticException.class, () -> engine.settle(
                "k-overflow",
                List.of(new OrderLine("A", Long.MAX_VALUE, 1), new OrderLine("B", 1L, 1)),
                CouponSpec.none()));
    }

    @Test
    void replaysIdenticalIdempotentRequestWithoutReexecuting() {
        AtomicInteger executions = new AtomicInteger();
        DiscountAllocator countingAllocator = new DiscountAllocator() {
            @Override
            public SettlementReceipt allocate(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon) {
                executions.incrementAndGet();
                return super.allocate(idempotencyKey, lines, coupon);
            }
        };
        OrderSettlementEngine engine = new OrderSettlementEngine(countingAllocator, new SettlementLedger());
        List<OrderLine> lines = List.of(new OrderLine("SKU-1", 500L, 2));
        CouponSpec coupon = new CouponSpec(1000, 0L, 0L);

        SettlementReceipt first = engine.settle("replay-key", lines, coupon);
        SettlementReceipt second = engine.settle("replay-key", lines, coupon);

        assertEquals(1, executions.get());
        assertEquals(first, second);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentParameters() {
        OrderSettlementEngine engine = new OrderSettlementEngine();
        CouponSpec coupon = CouponSpec.none();

        engine.settle("conflict-key", List.of(new OrderLine("SKU-1", 500L, 1)), coupon);

        assertThrows(IllegalArgumentException.class, () -> engine.settle(
                "conflict-key",
                List.of(new OrderLine("SKU-1", 600L, 1)),
                coupon));
        assertThrows(IllegalArgumentException.class, () -> engine.settle(
                "conflict-key",
                List.of(new OrderLine("SKU-1", 500L, 1)),
                new CouponSpec(1000, 0L, 0L)));
    }

    @Test
    void allowsRetryAfterFailedAttemptWithoutPoisoningIdempotencyKey() {
        AtomicBoolean failNext = new AtomicBoolean(true);
        DiscountAllocator flakyAllocator = new DiscountAllocator() {
            @Override
            public SettlementReceipt allocate(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon) {
                if (failNext.getAndSet(false)) {
                    throw new IllegalStateException("Transient downstream pricing failure");
                }
                return super.allocate(idempotencyKey, lines, coupon);
            }
        };
        OrderSettlementEngine engine = new OrderSettlementEngine(flakyAllocator, new SettlementLedger());
        List<OrderLine> lines = List.of(new OrderLine("SKU-RETRY", 1000L, 2));

        assertThrows(IllegalStateException.class, () -> engine.settle("retry-key", lines, CouponSpec.none()));

        SettlementReceipt retried = assertDoesNotThrow(
                () -> engine.settle("retry-key", lines, CouponSpec.none()),
                "Failed attempt must not poison idempotencyKey for retry");
        assertEquals(2000L, retried.payableTotalCents());
    }

    @Test
    void enforcesSingleExecutionUnderConcurrentSameIdempotencyKey() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        DiscountAllocator slowAllocator = new DiscountAllocator() {
            @Override
            public SettlementReceipt allocate(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon) {
                executions.incrementAndGet();
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return super.allocate(idempotencyKey, lines, coupon);
            }
        };
        OrderSettlementEngine engine = new OrderSettlementEngine(slowAllocator, new SettlementLedger());
        List<OrderLine> lines = List.of(
                new OrderLine("SKU-A", 1000L, 1),
                new OrderLine("SKU-B", 2000L, 1));
        CouponSpec coupon = new CouponSpec(1000, 2000L, 100L);

        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementReceipt>> futures = new ArrayList<>(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return engine.settle("concurrent-key", lines, coupon);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            SettlementReceipt expected = futures.get(0).get(10, TimeUnit.SECONDS);
            for (Future<SettlementReceipt> future : futures) {
                assertEquals(expected, future.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, executions.get());
    }
}
