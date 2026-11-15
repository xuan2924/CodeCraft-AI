package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.TestEvidence;
import ai.codecraft.workspace.v1.TestLogChunk;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import com.google.protobuf.ByteString;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommandSnapshotProjectionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ProblemVersionEntity PROBLEM = new ProblemVersionEntity("problem-version", "cart", "1.0.0", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "");

    @Test void nonzeroExitRemainsAnOrdinaryCommandOutcome() {
        var result = CommandSnapshotProjection.project(task(), snapshot().build(), PROBLEM);
        assertThat(result.state()).isEqualTo("EXITED");
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.synchronization()).isEqualTo("UNCHANGED");
        assertThat(result.changed()).isFalse();
        assertThat(result.synchronizedRevision()).isNull();
    }

    @Test void stopAndTimeoutCanStillConfirmTheirSourceChanges() {
        for (String state : new String[]{"STOPPED", "TIMED_OUT", "INTERRUPTED"}) {
            var source = snapshot().setExecution(execution().setState(state).setExitCode(137)).setSynchronization("SYNCED")
                .setMutation(MutationReceipt.newBuilder().setWorkspaceInstanceId("workspace").setOperationId("task")
                    .setRequestId("execution").setFingerprint("c".repeat(64)).setRevision(2).setChanged(true));
            var result = CommandSnapshotProjection.project(task(), source.build(), PROBLEM);
            assertThat(result.state()).isEqualTo(state);
            assertThat(result.exitCode()).isEqualTo(137);
            assertThat(result.changed()).isTrue();
            assertThat(result.synchronizedRevision()).isEqualTo("2");
        }
    }

    @Test void conflictNeverClaimsASynchronizedRevision() {
        var result = CommandSnapshotProjection.project(task(), snapshot().setSynchronization("CONFLICT").setSynchronizationReason("REVISION_CONFLICT").build(), PROBLEM);
        assertThat(result.changed()).isFalse();
        assertThat(result.synchronizationReason()).isEqualTo("REVISION_CONFLICT");
        assertThat(result.synchronizedRevision()).isNull();
    }

    @Test void logsPreserveChannelsAndDistinguishTruncationFromMissingSegments() {
        var source = snapshot().setExecution(execution().setDroppedBytes(12).setLogsIncomplete(true)
            .addLogs(TestLogChunk.newBuilder().setChannel("stdout").setData(ByteString.copyFromUtf8("out")))
            .addLogs(TestLogChunk.newBuilder().setChannel("stderr").setData(ByteString.copyFromUtf8("err"))));
        var result = CommandSnapshotProjection.project(task(), source.build(), PROBLEM);
        assertThat(result.logs()).extracting(log -> log.channel()).containsExactly("stdout", "stderr");
        assertThat(result.droppedBytes()).isEqualTo("12");
        assertThat(result.logsIncomplete()).isTrue();
    }

    @Test void rejectsAuthorityGradesForeignIdentitiesAndPrematurePublication() {
        var invalid = new CommandSnapshot[]{
            snapshot().setExecution(execution().setState("PASSED")).build(),
            snapshot().setExecution(execution().setExerciseId("other")).build(),
            snapshot().setExecution(execution().setTarget("base")).build(),
            snapshot().setExecution(execution().setEvidence(TestEvidence.newBuilder().setKind("SUREFIRE_XML"))).build(),
            snapshot().setExecution(execution().setQuiescent(false)).build(),
            snapshot().setExecution(execution().clearExitCode()).build(),
            snapshot().setExecution(execution().setExitCode(256)).build(),
            snapshot().setSynchronization("SYNCED").build()
        };
        for (var source : invalid) {
            assertThatThrownBy(() -> CommandSnapshotProjection.project(task(), source, PROBLEM)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void redactsCommandAndCheckpointToString() {
        assertThat(task().toString()).doesNotContain("secret-value");
        assertThat(CommandSnapshotProjection.project(task(), snapshot().build(), PROBLEM).toString()).doesNotContain("secret-value");
    }

    private static CommandSnapshot.Builder snapshot() { return CommandSnapshot.newBuilder().setExecution(execution()).setSynchronization("UNCHANGED"); }

    private static TestTaskSnapshot.Builder execution() {
        return TestTaskSnapshot.newBuilder().setExerciseId("exercise").setTaskId("task").setExecutionInstanceId("execution")
            .setWorkspaceInstanceId("workspace").setRevision(1).setTarget("bash").setState("EXITED").setExitCode(7)
            .setStateVersion(1).setStartedAtUnixMillis(NOW.toEpochMilli()).setFinishedAtUnixMillis(NOW.plusSeconds(1).toEpochMilli())
            .setQuiescent(true).setCleanup("SUCCEEDED").setCopyCleanup("SUCCEEDED")
            .setProblem(ProblemIdentity.newBuilder().setProblemId(PROBLEM.problemId()).setVersion(PROBLEM.version())
                .setPackageSha256(PROBLEM.packageSha256()).setExecutionImageDigest(PROBLEM.executionImageDigest()).setPlatform(PROBLEM.platform()));
    }

    private static CommandTaskEntity task() {
        return new CommandTaskEntity("task", "exercise", "owner", "workspace", "execution", "session", 1, "request", "fingerprint",
            "echo secret-value", ".", 1, 2, NOW.plusSeconds(60), "A".repeat(26), "RUNNING", true, false, false, "PENDING", null,
            0, false, false, NOW, null);
    }
}
