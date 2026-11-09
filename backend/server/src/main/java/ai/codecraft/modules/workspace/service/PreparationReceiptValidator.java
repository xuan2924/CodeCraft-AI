package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import org.springframework.stereotype.Component;

@Component
public class PreparationReceiptValidator {
    public boolean matches(WorkspaceSnapshot receipt, String instanceId, String operationId, ProblemVersionEntity problem) {
        return problem != null && receipt.getPrepared() && !receipt.getCancelled() && receipt.getRevision() == 0
            && receipt.getWorkspaceInstanceId().equals(instanceId)
            && receipt.getOperationId().equals(operationId)
            && receipt.getProblem().getProblemId().equals(problem.problemId())
            && receipt.getProblem().getVersion().equals(problem.version())
            && receipt.getProblem().getPackageSha256().equals(problem.packageSha256())
            && receipt.getProblem().getExecutionImageDigest().equals(problem.executionImageDigest())
            && receipt.getProblem().getPlatform().equals(problem.platform());
    }
}
