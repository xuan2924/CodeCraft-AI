package settlement.idempotency;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import settlement.model.SettlementReceipt;

public class SettlementLedger {
    private static final class RefundState {
        private int refundedQuantity;
        private long refundedCents;
    }

    private static final class Entry {
        private final String fingerprint;
        private final CompletableFuture<SettlementReceipt> future = new CompletableFuture<>();
        private final Map<String, RefundState> refundStates = new HashMap<>();

        private Entry(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public SettlementReceipt executeIdempotent(
            String idempotencyKey,
            String requestFingerprint,
            Supplier<SettlementReceipt> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(action, "action");

        while (true) {
            Entry mine = new Entry(requestFingerprint);
            Entry existing = entries.putIfAbsent(idempotencyKey, mine);
            if (existing == null) {
                try {
                    SettlementReceipt receipt = Objects.requireNonNull(
                            action.get(), "Settlement receipt must not be null");
                    mine.future.complete(receipt);
                    return receipt;
                } catch (Throwable failure) {
                    entries.remove(idempotencyKey, mine);
                    mine.future.completeExceptionally(failure);
                    throw failure;
                }
            }
            if (!existing.fingerprint.equals(requestFingerprint)) {
                throw new IllegalArgumentException(
                        "Idempotency key already used with different request parameters: " + idempotencyKey);
            }
            try {
                return existing.future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting idempotent settlement", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Idempotent settlement failed", cause);
            }
        }
    }

    public Optional<SettlementReceipt> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        Entry entry = entries.get(idempotencyKey);
        if (entry == null || !entry.future.isDone() || entry.future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.future.getNow(null));
    }

    public long refundLine(String idempotencyKey, String skuId, int refundQuantity) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (refundQuantity <= 0) {
            throw new IllegalArgumentException("refundQuantity must be positive");
        }

        Entry entry = entries.get(idempotencyKey);
        if (entry == null || !entry.future.isDone() || entry.future.isCompletedExceptionally()) {
            throw new IllegalArgumentException("Settled order not found for idempotencyKey: " + idempotencyKey);
        }
        SettlementReceipt receipt = entry.future.getNow(null);
        if (receipt == null) {
            throw new IllegalArgumentException("Settled order not found for idempotencyKey: " + idempotencyKey);
        }

        SettlementReceipt.LineAllocation targetLine = null;
        for (SettlementReceipt.LineAllocation line : receipt.lineAllocations()) {
            if (line.skuId().equals(skuId)) {
                targetLine = line;
                break;
            }
        }
        if (targetLine == null) {
            throw new IllegalArgumentException("Unknown skuId in settled order: " + skuId);
        }

        synchronized (entry) {
            RefundState state = entry.refundStates.computeIfAbsent(skuId, ignored -> new RefundState());
            int remaining = targetLine.settledQuantity() - state.refundedQuantity;
            if (refundQuantity > remaining) {
                throw new IllegalArgumentException(
                        "Refund quantity " + refundQuantity + " exceeds remaining " + remaining + " for skuId " + skuId);
            }
            int newRefundedQuantity = state.refundedQuantity + refundQuantity;
            long newCumulativeCents = BigInteger.valueOf(targetLine.payableCents())
                    .multiply(BigInteger.valueOf(newRefundedQuantity))
                    .divide(BigInteger.valueOf(targetLine.settledQuantity()))
                    .longValueExact();
            long refundCents = newCumulativeCents - state.refundedCents;
            state.refundedQuantity = newRefundedQuantity;
            state.refundedCents = newCumulativeCents;
            return refundCents;
        }
    }
}
