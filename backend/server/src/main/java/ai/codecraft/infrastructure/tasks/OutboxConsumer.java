package ai.codecraft.infrastructure.tasks;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxConsumer {
    private final OutboxStore store;
    private final OutboxHandler handler;
    public OutboxConsumer(OutboxStore store, OutboxHandler handler) { this.store = store; this.handler = handler; }
    public void accept(String id) {
        String token = UUID.randomUUID().toString();
        var event = store.begin(id, token);
        if (event == null) return;
        try { handler.handle(event); }
        catch (RuntimeException failure) { store.failed(event, token); return; }
        store.complete(event, token);
    }
}
