package ai.codecraft.infrastructure.tasks.service;

import ai.codecraft.infrastructure.tasks.OutboxEvent;
import ai.codecraft.infrastructure.tasks.OutboxRepository;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short PostgreSQL transactions only. No broker or business IO under these row locks. */
@Service
public class OutboxStore {
    private final OutboxRepository repository;
    public OutboxStore(OutboxRepository repository) { this.repository = repository; }
    @Transactional public List<OutboxEvent> claim(String lane) { return repository.claim(lane, UUID.randomUUID().toString()); }
    @Transactional public void published(OutboxEvent event) { repository.published(event.id(), event.leaseToken()); }
    @Transactional public void publishFailed(OutboxEvent event) { repository.publishFailed(event.id(), event.leaseToken()); }
    @Transactional public OutboxEvent begin(String id, String token) {
        UUID.fromString(id);
        var event = repository.lock(id);
        if (event == null || event.state().equals("DONE") || event.state().equals("DEAD")) return null;
        return repository.begin(id, token) == 1 ? event : null;
    }
    @Transactional public void complete(OutboxEvent event, String token) {
        var current = repository.lock(event.id());
        if (current == null || current.state().equals("DONE") || current.state().equals("DEAD")) return;
        if (repository.finish(event.id(), token, "DONE") == 1) repository.outcome(event.id(), "DONE", 0, 0);
    }
    @Transactional public void failed(OutboxEvent event, String token) {
        var current = repository.lock(event.id());
        if (current == null || current.state().equals("DONE") || current.state().equals("DEAD")) return;
        int failures = current.failures() + 1;
        if (failures >= 5) {
            if (repository.finish(event.id(), token, "DEAD") != 1) return;
            repository.dead(event.id(), "HANDLER_FAILED", failures);
            repository.outcome(event.id(), "DEAD", 1, 0);
        } else {
            int delay = retryDelay(failures);
            if (repository.release(event.id(), token, delay) == 1) repository.outcome(event.id(), "PENDING", 1, delay);
        }
    }
    public static int retryDelay(int failures) { return Math.min(300, 1 << Math.min(8, Math.max(1, failures))); }
    @Transactional public void prune() { repository.prune(); }
    public long bytes(String exercise) { return repository.bytes(exercise); }
    public boolean active(String exercise) { return repository.active(exercise); }
    @Transactional public void retire(String exercise) { repository.retire(exercise); }
}
