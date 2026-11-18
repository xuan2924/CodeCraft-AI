package limiter;

import java.time.Clock;
import java.util.Objects;

public class SlidingWindow {
    public SlidingWindow(Clock clock, long windowMillis, Rule rule) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(rule);
        if (windowMillis <= 0) throw new IllegalArgumentException("Invalid window");
    }

    public boolean acquire(QuotaKey key, long units, boolean priority) {
        Objects.requireNonNull(key);
        if (units <= 0) throw new IllegalArgumentException("Invalid units");
        return true; // Implement rolling quota accounting.
    }

    public int retainedKeys() { return 0; }

    public void update(Rule rule) { Objects.requireNonNull(rule); }
}
