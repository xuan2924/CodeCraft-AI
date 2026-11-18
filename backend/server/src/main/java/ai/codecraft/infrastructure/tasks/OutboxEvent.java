package ai.codecraft.infrastructure.tasks;

public record OutboxEvent(String id, String kind, String aggregateId, String exerciseId, long generation,
    int attempt, String lane, String state, String leaseToken, int failures) { }
