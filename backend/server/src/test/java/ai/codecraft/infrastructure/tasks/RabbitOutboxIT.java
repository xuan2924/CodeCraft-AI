package ai.codecraft.infrastructure.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

@EnabledIfEnvironmentVariable(named = "M3_IT_RUN", matches = "disposable")
class RabbitOutboxIT {
    @Test void realMandatoryConfirmedPublishSeparatedConsumersAndDeadLetter() throws Exception {
        var factory = new CachingConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("M3_IT_RABBIT_PORT")));
        factory.setUsername("fixture"); factory.setPassword("fixture");
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true);
        var configuration = new RabbitTaskConfiguration(); var admin = new RabbitAdmin(factory);
        var topology = configuration.taskTopology();
        var fastConsumer = mock(OutboxConsumer.class); var slowConsumer = mock(OutboxConsumer.class);
        var fast = configuration.interactiveTasks(factory, fastConsumer); var slow = configuration.slowTasks(factory, slowConsumer);
        OutboxRelay relay = null;
        try {
            topology.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
            topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
            topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
            var fastReady = new CountDownLatch(1); var slowReady = new CountDownLatch(1);
            doAnswer(call -> { fastReady.countDown(); return null; }).when(fastConsumer).accept(anyString());
            doAnswer(call -> { slowReady.countDown(); return null; }).when(slowConsumer).accept(anyString());
            fast.afterPropertiesSet(); slow.afterPropertiesSet(); fast.start(); slow.start();
            var template = new RabbitTemplate(factory);
            @SuppressWarnings("unchecked") var provider = (ObjectProvider<RabbitTemplate>) mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(template);
            var store = mock(OutboxStore.class);
            var interactive = event("interactive", "LEASED"); var report = event("slow", "LEASED"); var dead = event("interactive", "DEAD");
            when(store.claim("interactive")).thenReturn(List.of(interactive, dead));
            when(store.claim("slow")).thenReturn(List.of(report));
            relay = new OutboxRelay(store, mock(OutboxConsumer.class), new AsyncTaskProperties("rabbit"), provider);
            relay.relay("interactive"); relay.relay("slow");
            assertThat(fastReady.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(slowReady.await(5, TimeUnit.SECONDS)).isTrue();
            verify(fastConsumer).accept(interactive.id()); verify(slowConsumer).accept(report.id());
            verify(store).published(interactive); verify(store).published(report); verify(store).published(dead);
            var message = template.receive(RabbitTaskConfiguration.EXCHANGE + ".interactive.dead", 5000);
            assertThat(message).isNotNull(); assertThat(new String(message.getBody(), StandardCharsets.US_ASCII)).isEqualTo(dead.id());
            // A missing binding must return the message rather than mark it delivered in PostgreSQL.
            admin.deleteQueue(RabbitTaskConfiguration.EXCHANGE + ".slow");
            relay.relay("slow"); verify(store).publishFailed(report);
        } finally {
            fast.destroy(); slow.destroy(); if (relay != null) relay.close();
            topology.getDeclarablesByType(Queue.class).forEach(queue -> admin.deleteQueue(queue.getName()));
            admin.deleteExchange(RabbitTaskConfiguration.EXCHANGE); factory.destroy();
        }
    }
    private OutboxEvent event(String lane, String state) {
        return new OutboxEvent(UUID.randomUUID().toString(), lane.equals("slow") ? "REPORT" : "TEST", UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 0, 0, lane, state, UUID.randomUUID().toString(), 0);
    }
}
