package ai.codecraft.modules.modelaccess.model.dto;

/** Exact non-secret revision selected by an admitted operation; never follows mutable configuration silently. */
public record ModelInvocationReferenceDTO(String connectionId, String modelConfigurationId,
    long connectionConfigurationVersion, long modelConfigurationVersion) { }
