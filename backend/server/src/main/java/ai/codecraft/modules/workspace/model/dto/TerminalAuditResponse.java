package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record TerminalAuditResponse(String commandId, String state, Instant createdAt, Instant openedAt, Instant closedAt,
    String baseRevision, String synchronizedRevision, String synchronization, String reason, boolean pruned) { }
