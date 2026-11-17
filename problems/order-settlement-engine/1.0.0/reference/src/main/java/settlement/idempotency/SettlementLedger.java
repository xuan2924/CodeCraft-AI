package settlement.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import settlement.model.SettlementReceipt;

public class SettlementLedger {
    private static final class Entry {
        private final String fingerprint;
        private final CompletableFuture<SettlementReceipt> future = new CompletableFuture<>();

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
}
