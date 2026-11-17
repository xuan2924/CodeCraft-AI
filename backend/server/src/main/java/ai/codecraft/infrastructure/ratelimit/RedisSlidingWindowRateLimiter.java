package ai.codecraft.infrastructure.ratelimit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "codecraft.rate-limit.backend", havingValue = "redis")
public class RedisSlidingWindowRateLimiter implements SlidingWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("ratelimit/sliding-window.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final RateLimitProperties properties;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis, Clock clock, RateLimitProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public boolean admit(List<RateLimitPolicy> policies) {
        List<RateLimitPolicy> unique = RateLimitRequests.validate(policies);
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(clock.millis()));
        arguments.add(properties.capacity().toString());
        for (RateLimitPolicy policy : unique) {
            arguments.add(policy.bucket());
            arguments.add(Long.toString(policy.window().toMillis()));
            arguments.add(Integer.toString(policy.limit()));
        }
        // No fallback: an unknown Redis outcome must never reset admission in PostgreSQL.
        Long result = redis.execute(SCRIPT, List.of("codecraft:{rate-limit}:windows"), arguments.toArray());
        if (result == null || (result != 0 && result != 1)) throw new IllegalStateException("Rate admission unavailable");
        return result == 1;
    }
}
