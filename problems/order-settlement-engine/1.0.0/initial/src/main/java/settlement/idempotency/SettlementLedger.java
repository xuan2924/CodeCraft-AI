package settlement.idempotency;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import settlement.model.SettlementReceipt;

public class SettlementLedger {
    private enum Status {
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }

    private record LedgerEntry(Status status, SettlementReceipt receipt) {
    }

    private final Map<String, LedgerEntry> entries = new ConcurrentHashMap<>();

    public SettlementReceipt executeIdempotent(
            String idempotencyKey,
            String requestFingerprint,
            Supplier<SettlementReceipt> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(action, "action");

        // Bug 1: Non-atomic check-then-act allows concurrent callers with the same key to execute action multiple times.
        // Bug 2: requestFingerprint is ignored, so reusing the same key with different parameters is not rejected.
        LedgerEntry existing = entries.get(idempotencyKey);
        if (existing != null) {
            if (existing.status() == Status.SUCCEEDED) {
                return existing.receipt();
            }
            if (existing.status() == Status.FAILED) {
                // Bug 3: FAILED status permanently blocks subsequent retries.
                throw new IllegalStateException("Idempotency key is not retryable: " + idempotencyKey);
            }
            // Note: IN_PROGRESS falls through here too, compounding the non-atomic race.
        }

        entries.put(idempotencyKey, new LedgerEntry(Status.IN_PROGRESS, null));
        try {
            SettlementReceipt receipt = Objects.requireNonNull(action.get(), "Settlement receipt must not be null");
            entries.put(idempotencyKey, new LedgerEntry(Status.SUCCEEDED, receipt));
            return receipt;
        } catch (RuntimeException error) {
            entries.put(idempotencyKey, new LedgerEntry(Status.FAILED, null));
            throw error;
        }
    }

    public Optional<SettlementReceipt> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        LedgerEntry entry = entries.get(idempotencyKey);
        if (entry == null || entry.status() != Status.SUCCEEDED) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.receipt());
    }
}
