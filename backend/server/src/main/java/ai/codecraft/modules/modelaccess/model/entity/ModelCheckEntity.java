package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

/** No credentials or provider payloads are retained by check admission. */
public record ModelCheckEntity(String id, String userId, String connectionId, String modelConfigurationId,
    long connectionConfigurationVersion, long modelConfigurationVersion, String status, Instant startedAt) { }
