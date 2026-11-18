package limiter;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class SlidingWindow {
    private record Debit(QuotaKey key, long at, long units) { }
    private static class Bucket {

        long used;
    }
    private final ArrayDeque<Debit> expiry = new ArrayDeque<>();
    public synchronized int retainedKeys() { return buckets.size(); }
    private final Clock clock;
    private final long window;
    private final Map<QuotaKey, Bucket> buckets = new HashMap<>();
    private Rule rule;
    private long watermark = Long.MIN_VALUE;

    public SlidingWindow(Clock clock, long windowMillis, Rule rule) {
        this.clock = Objects.requireNonNull(clock);
        this.rule = Objects.requireNonNull(rule);
        if (windowMillis <= 0) throw new IllegalArgumentException("Invalid window");
        this.window = windowMillis;
    }

    public synchronized boolean acquire(QuotaKey key, long units, boolean priority) {
        Objects.requireNonNull(key);
        if (units <= 0) throw new IllegalArgumentException("Invalid units");
        watermark = Math.max(watermark, clock.millis());
        if (watermark >= Long.MIN_VALUE + window) {
            long cutoff = watermark - window;
            while (!expiry.isEmpty() && expiry.peek().at <= cutoff) {
                var debit = expiry.remove();
                var expired = buckets.get(debit.key);
                expired.used -= debit.units;
                if (expired.used == 0) buckets.remove(debit.key);
            }
        }
        var bucket = buckets.get(key);
        long limit = priority ? rule.capacity() : rule.capacity() - rule.reserve();
        if (units > limit - (bucket == null ? 0 : bucket.used)) return false;
        if (bucket == null) bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        expiry.add(new Debit(key, watermark, units));
        bucket.used += units;
        return true;
    }

    public synchronized void update(Rule rule) { this.rule = Objects.requireNonNull(rule); }
}
