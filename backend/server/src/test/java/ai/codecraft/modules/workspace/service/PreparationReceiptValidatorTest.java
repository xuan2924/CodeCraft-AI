package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparationReceiptValidatorTest {
    @Test
    void onlyAnExactPreparedRevisionZeroReceiptMatches() {
        PreparationReceiptValidator validator = new PreparationReceiptValidator();
        ProblemVersionEntity problem = new ProblemVersionEntity("version-id", "cart-total", "1.0.0",
            "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}");
        ProblemIdentity identity = ProblemIdentity.newBuilder().setProblemId(problem.problemId()).setVersion(problem.version())
            .setPackageSha256(problem.packageSha256()).setExecutionImageDigest(problem.executionImageDigest()).setPlatform(problem.platform()).build();
        WorkspaceSnapshot receipt = WorkspaceSnapshot.newBuilder().setWorkspaceInstanceId("instance").setOperationId("operation")
            .setProblem(identity).setPrepared(true).build();
        assertThat(validator.matches(receipt, "instance", "operation", problem)).isTrue();
        assertThat(validator.matches(receipt, "instance", "operation", null)).isFalse();
        for (WorkspaceSnapshot invalid : List.of(
            receipt.toBuilder().setPrepared(false).build(),
            receipt.toBuilder().setCancelled(true).build(),
            receipt.toBuilder().setRevision(1).build(),
            receipt.toBuilder().setWorkspaceInstanceId("another").build(),
            receipt.toBuilder().setOperationId("another").build(),
            receipt.toBuilder().clearProblem().build(),
            receipt.toBuilder().setProblem(identity.toBuilder().setProblemId("another")).build(),
            receipt.toBuilder().setProblem(identity.toBuilder().setVersion("2.0.0")).build(),
            receipt.toBuilder().setProblem(identity.toBuilder().setPackageSha256("c".repeat(64))).build(),
            receipt.toBuilder().setProblem(identity.toBuilder().setExecutionImageDigest("sha256:" + "d".repeat(64))).build(),
            receipt.toBuilder().setProblem(identity.toBuilder().setPlatform("linux/arm64")).build())) {
            assertThat(validator.matches(invalid, "instance", "operation", problem)).isFalse();
        }
    }
}
