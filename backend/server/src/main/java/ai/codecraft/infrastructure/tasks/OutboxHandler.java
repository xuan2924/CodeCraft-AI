package ai.codecraft.infrastructure.tasks;

/** Executes one persisted identity, never trusts a broker payload as execution authority. */
public interface OutboxHandler {
    void handle(OutboxEvent event);
}
