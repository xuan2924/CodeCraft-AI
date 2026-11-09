package ai.codecraft.modules.workspace.model.dto;

public record WorkspaceOperationResponse(String operationId, String exerciseId, String state, String queryUrl,
                                         String revision, Boolean changed, String failureCode) {
    public WorkspaceOperationResponse(String operationId, String exerciseId, String state, String queryUrl) {
        this(operationId, exerciseId, state, queryUrl, null, null, null);
    }
}
