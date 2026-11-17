package ai.codecraft.infrastructure.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class TaskRealtimeConfiguration {
    @Bean
    @ConditionalOnProperty(name = "codecraft.realtime.backend", havingValue = "local", matchIfMissing = true)
    TaskRealtimeBus localTaskBus() { return new TaskRealtimeBus(null); }

    @Bean
    @ConditionalOnProperty(name = "codecraft.realtime.backend", havingValue = "redis")
    TaskRealtimeBus redisTaskBus(StringRedisTemplate redis) { return new TaskRealtimeBus(redis); }

    @Bean
    @ConditionalOnProperty(name = "codecraft.realtime.backend", havingValue = "redis")
    RedisMessageListenerContainer taskHintListener(RedisConnectionFactory factory, TaskRealtimeBus bus) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // Delivery only updates bounded memory and schedules work; no database or socket writes here.
        container.setTaskExecutor(Runnable::run);
        container.addMessageListener((message, pattern) -> bus.receive(message.getBody()), new ChannelTopic(TaskRealtimeBus.CHANNEL));
        return container;
    }
}
