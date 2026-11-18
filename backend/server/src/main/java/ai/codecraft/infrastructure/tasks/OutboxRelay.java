package ai.codecraft.infrastructure.tasks;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Separate bounded lanes: a slow evaluator cannot occupy the interactive relay/consumer. */
@Component
public class OutboxRelay {
    private final OutboxStore store;
    private final OutboxConsumer consumer;
    private final AsyncTaskProperties properties;
    private final ObjectProvider<RabbitTemplate> rabbit;
    private final List<ThreadPoolExecutor> workers = List.of(pool("interactive"), pool("slow"));
    public OutboxRelay(OutboxStore store, OutboxConsumer consumer, AsyncTaskProperties properties, ObjectProvider<RabbitTemplate> rabbit) {
        this.store = store; this.consumer = consumer; this.properties = properties; this.rabbit = rabbit;
    }
    private static ThreadPoolExecutor pool(String lane) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().name("outbox-" + lane + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(fixedDelay = 500)
    public void tick() {
        for (int i = 0; i < 2; i++) {
            String lane = i == 0 ? "interactive" : "slow";
            try { workers.get(i).execute(() -> relay(lane)); }
            catch (java.util.concurrent.RejectedExecutionException busy) { /* Existing lane owns its batch. */ }
        }
    }
    public void relay(String lane) {
        try {
            for (var event : store.claim(lane)) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    if (properties.backend().equals("rabbit")) publish(event);
                    else if (!event.state().equals("DEAD")) consumer.accept(event.id());
                    store.published(event);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); return; // Claim lease recovers the unknown publish outcome.
                } catch (Exception failed) { store.publishFailed(event); }
            }
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(OutboxRelay.class).warn("Outbox lane deferred: {}", lane);
        }
    }
    private void publish(OutboxEvent event) throws Exception {
        var attributes = new MessageProperties();
        attributes.setDeliveryMode(MessageDeliveryMode.PERSISTENT); attributes.setMessageId(event.id());
        attributes.setContentType("text/plain");
        var confirmation = new CorrelationData(java.util.UUID.randomUUID().toString());
        var template = rabbit.getObject();
        template.setMandatory(true);
        template.send(RabbitTaskConfiguration.EXCHANGE, event.lane() + (event.state().equals("DEAD") ? ".dead" : ""),
            new Message(event.id().getBytes(StandardCharsets.US_ASCII), attributes), confirmation);
        if (!confirmation.getFuture().get(3, TimeUnit.SECONDS).isAck() || confirmation.getReturned() != null) {
            throw new IllegalStateException("Broker did not durably route task");
        }
    }
    @Scheduled(fixedDelay = 60000) public void prune() { store.prune(); }
    @PreDestroy public void close() { workers.forEach(ThreadPoolExecutor::shutdownNow); }
}
