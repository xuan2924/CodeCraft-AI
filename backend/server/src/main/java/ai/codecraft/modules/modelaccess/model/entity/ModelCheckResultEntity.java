package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

public record ModelCheckResultEntity(String id, int ordinal, String status, Instant completedAt, String errorCode, boolean reused) { }
