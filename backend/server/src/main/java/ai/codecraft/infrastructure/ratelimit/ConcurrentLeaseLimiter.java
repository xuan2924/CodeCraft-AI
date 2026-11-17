package ai.codecraft.infrastructure.ratelimit;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Terminal transport quota, not execution authority. Expiry reclaims crashed nodes; tokens fence stale release. */
@Component
public final class ConcurrentLeaseLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("ratelimit/concurrent-lease.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, Lease> local = new HashMap<>();
    public ConcurrentLeaseLimiter(Clock clock) { this.redis = null; this.clock = clock; }

    @Autowired
    public ConcurrentLeaseLimiter(StringRedisTemplate redis, Clock clock, RateLimitProperties properties) {
        this.redis = properties.backend().equals("redis") ? redis : null; this.clock = clock;
    }

    public boolean acquire(String task, String token) { return execute("acquire", task, token); }
    public boolean renew(String task, String token) { return execute("renew", task, token); }
    public void release(String task, String token) { execute("release", task, token); }

    private synchronized boolean execute(String operation, String task, String token) {
        if (task == null || task.isBlank() || task.length() > 128 || token == null || token.isBlank() || token.length() > 128) {
            throw new IllegalArgumentException("Invalid lease identity");
        }
        if (redis != null) {
            Long result = redis.execute(SCRIPT, List.of("codecraft:{terminal-quota}:leases"), operation, task, token, "64", "15000");
            if (result == null || (result != 0 && result != 1)) throw new IllegalStateException("Lease admission unavailable");
            return result == 1;
        }
        long now = clock.millis();
        local.values().removeIf(lease -> lease.expires() <= now);
        Lease current = local.get(task);
        boolean owned = current != null && current.token().equals(token);
        if (operation.equals("release")) { if (owned) local.remove(task); return owned; }
        if (operation.equals("renew") && !owned) return false;
        if (operation.equals("acquire") && (current != null || local.size() >= 64)) return false;
        local.put(task, new Lease(token, now + 15000));
        return true;
    }
    private record Lease(String token, long expires) { }
}
