package ai.codecraft.modules.modelaccess.model.dto;

import java.time.Instant;

public record ModelCheckResponse(String id, String modelConfigurationId, String connectionConfigurationVersion,
    String modelConfigurationVersion, String status, Instant startedAt) implements ModelCommandResponse { }
