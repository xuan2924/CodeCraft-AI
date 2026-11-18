package ai.codecraft.infrastructure.tasks;

import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "codecraft.async.backend", havingValue = "rabbit")
public class RabbitTaskConfiguration {
    public static final String EXCHANGE = "codecraft.tasks.v1";
    @Bean public org.springframework.amqp.rabbit.core.RabbitAdmin taskAdmin(ConnectionFactory factory) {
        return new org.springframework.amqp.rabbit.core.RabbitAdmin(factory);
    }
    @Bean public Declarables taskTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var declarations = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        declarations.add(exchange);
        for (String lane : java.util.List.of("interactive", "slow")) {
            var queue = QueueBuilder.durable(EXCHANGE + "." + lane).maxLength(5000)
                .overflow(QueueBuilder.Overflow.rejectPublish).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(lane + ".dead").build();
            var dead = QueueBuilder.durable(EXCHANGE + "." + lane + ".dead").maxLength(10000).overflow(QueueBuilder.Overflow.rejectPublish).build();
            declarations.add(queue); declarations.add(dead);
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(lane));
            declarations.add(BindingBuilder.bind(dead).to(exchange).with(lane + ".dead"));
        }
        return new Declarables(declarations);
    }
    @Bean public SimpleMessageListenerContainer interactiveTasks(ConnectionFactory factory, OutboxConsumer consumer) {
        return listener(factory, consumer, "interactive", 2);
    }
    @Bean public SimpleMessageListenerContainer slowTasks(ConnectionFactory factory, OutboxConsumer consumer) {
        return listener(factory, consumer, "slow", 1);
    }
    private SimpleMessageListenerContainer listener(ConnectionFactory factory, OutboxConsumer consumer, String lane, int concurrency) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(EXCHANGE + "." + lane);
        container.setConcurrentConsumers(concurrency);
        container.setMaxConcurrentConsumers(concurrency);
        container.setPrefetchCount(1);
        container.setMissingQueuesFatal(false);
        container.setRecoveryInterval(5000);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            long tag = message.getMessageProperties().getDeliveryTag();
            String id = new String(message.getBody(), StandardCharsets.US_ASCII);
            if (message.getBody().length != 36 || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                channel.basicReject(tag, false); return;
            }
            try { consumer.accept(id); channel.basicAck(tag, false); }
            catch (RuntimeException unavailable) {
                // Database failure: retain broker ownership, bound outage redelivery pressure.
                try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                channel.basicNack(tag, false, true);
            }
        });
        return container;
    }
}
