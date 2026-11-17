package ai.codecraft.infrastructure.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisRateLimitConfiguration {
    @Bean
    LettuceClientConfigurationBuilderCustomizer rateLimitRedisTimeouts() {
        return builder -> builder.commandTimeout(Duration.ofSeconds(2)).clientOptions(ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
            .requestQueueSize(64)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    }
}
