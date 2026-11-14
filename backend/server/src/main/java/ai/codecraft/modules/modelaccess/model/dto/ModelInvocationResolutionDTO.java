package ai.codecraft.modules.modelaccess.model.dto;

import ai.codecraft.common.exception.ErrorCode;

/** Expected rejection is data so a joined dispatch transaction can commit its terminal decision. */
public record ModelInvocationResolutionDTO(ModelInvocationDTO invocation, ErrorCode error) {
    @Override public String toString() { return "ModelInvocationResolutionDTO[redacted]"; }
}
