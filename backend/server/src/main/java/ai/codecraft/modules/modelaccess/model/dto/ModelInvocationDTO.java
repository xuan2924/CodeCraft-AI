package ai.codecraft.modules.modelaccess.model.dto;

/** Immutable resolved configuration for one call only; never a persistence or HTTP response type. */
public record ModelInvocationDTO(ModelConnectionResponse connection, ModelConfigurationResponse model, String credential) {
    @Override public String toString() { return "ModelInvocationDTO[REDACTED]"; }
}
