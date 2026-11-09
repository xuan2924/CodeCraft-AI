package ai.codecraft.modules.workspace.model.dto;

public record ControlResponse(String exerciseId, String operationId, String state, String generation,
                              boolean held, boolean credentialDelivered, String credential) {
    @Override public String toString() { return "ControlResponse[exerciseId=" + exerciseId + ", state=" + state + "]"; }
}
