package ai.codecraft.modules.modelaccess.model.dto;

import java.time.Instant;

public record ModelCheckItemResponse(String status, Instant completedAt, Instant validUntil, boolean reused, boolean valid, String errorCode) { }
