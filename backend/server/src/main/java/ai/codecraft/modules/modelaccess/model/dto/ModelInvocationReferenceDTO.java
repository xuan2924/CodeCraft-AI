package ai.codecraft.modules.modelaccess.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** Exact non-secret revision selected by an admitted operation; never follows mutable configuration silently. */
public record ModelInvocationReferenceDTO(String connectionId, String modelConfigurationId,
    @JsonSerialize(using = ToStringSerializer.class) long connectionConfigurationVersion,
    @JsonSerialize(using = ToStringSerializer.class) long modelConfigurationVersion) { }
