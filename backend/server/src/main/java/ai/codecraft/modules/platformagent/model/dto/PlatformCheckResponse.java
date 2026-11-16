package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import java.time.Instant;

public record PlatformCheckResponse(String id, String versionId, String credentialRevision, String state,
    Instant createdAt, Instant finishedAt, String errorCode, ModelUsageReceiptDTO usage) { }
