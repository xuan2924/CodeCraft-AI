package ai.codecraft.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RATE_LIMIT_REDIS_IT_RUN", matches = "disposable")
class RedisTaskRealtimeIT {
    @Test void realPubSubConnectsIndependentNodeHubs() throws Exception {
        int port = Integer.parseInt(System.getenv("RATE_LIMIT_REDIS_PORT"));
        var factoryA = new LettuceConnectionFactory("127.0.0.1", port);
        var factoryB = new LettuceConnectionFactory("127.0.0.1", port);
        factoryA.afterPropertiesSet(); factoryA.start(); factoryB.afterPropertiesSet(); factoryB.start();
        var busA = new TaskRealtimeBus(new StringRedisTemplate(factoryA));
        var busB = new TaskRealtimeBus(new StringRedisTemplate(factoryB));
        var configuration = new TaskRealtimeConfiguration();
        var listenerA = configuration.taskHintListener(factoryA, busA);
        var listenerB = configuration.taskHintListener(factoryB, busB);
        try {
            listenerA.afterPropertiesSet(); listenerB.afterPropertiesSet(); listenerA.start(); listenerB.start();
            var hubA = new TransientTaskStreamHub(busA);
            var hubB = new TransientTaskStreamHub(busB);
            var received = new CountDownLatch(1);
            hubB.subscribe(task -> received.countDown());
            hubA.preview("task-it", "turn-it", new byte[] {1, 2, 3});
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(hubB.latest("task-it").frame()).containsExactly(1, 2, 3);
        } finally {
            listenerA.destroy(); listenerB.destroy(); busA.close(); busB.close(); factoryA.destroy(); factoryB.destroy();
        }
    }
}
