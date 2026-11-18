package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record SandboxNodeDTO(String id, String endpoint, String storageId, String hostEpoch, int activeSlots,
    int maxSlots, Instant healthyUntil, int reservations, boolean ready) {
    public boolean healthy(Instant now) { return storageId != null && healthyUntil != null && healthyUntil.isAfter(now); }
    public int free() { return Math.max(0, maxSlots - activeSlots - reservations); }
}
