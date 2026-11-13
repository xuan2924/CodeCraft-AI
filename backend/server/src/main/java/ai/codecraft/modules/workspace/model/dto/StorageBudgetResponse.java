package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record StorageBudgetResponse(long limitBytes, long recoveryBytes, long goBytes, long databaseBytes,
    long reservedBytes, Instant measuredAt) {}
