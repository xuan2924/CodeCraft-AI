package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

public record ModelCheckAuthorityEntity(String checkId, String userId, String sessionHash, long authVersion, Instant notAfter, String workerId) {
    @Override public String toString() { return "ModelCheckAuthorityEntity[redacted]"; }
}
