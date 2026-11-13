package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record StorageMeterEntity(String exerciseId, String meterEpoch, long sequence, long bytes, Instant checkedAt) {}
