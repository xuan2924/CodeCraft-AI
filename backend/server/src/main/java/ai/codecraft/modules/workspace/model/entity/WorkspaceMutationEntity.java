package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record WorkspaceMutationEntity(String operationId, String payload, long gateEpoch, Instant notAfter,
                                      boolean dispatched, Long revision, Boolean changed, String failureCode) {
    @Override public String toString() { return "WorkspaceMutationEntity[operationId=" + operationId + "]"; }
}
