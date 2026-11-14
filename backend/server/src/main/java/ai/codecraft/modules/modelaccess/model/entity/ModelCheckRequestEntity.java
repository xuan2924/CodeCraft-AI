package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

public record ModelCheckRequestEntity(String id, String checkId, int ordinal, String workerId, String status,
    Instant dispatchedAt, Instant notAfter, int decodedBytes) { }
