package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.copilot.model.dto.CopilotCommandRecoveryDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;
import ai.codecraft.modules.workspace.model.dto.CommandEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotCommandResultFormatterTest {
    private final CopilotCommandResultFormatter formatter = new CopilotCommandResultFormatter();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final CopilotCommandEntity binding = new CopilotCommandEntity("tool", 1, "parent", "exercise", "owner", "workspace", 1, 1,
        "exit 7", ".", "fingerprint", "ASK", 0, "WAITING", "child", "decision", "fingerprint", "APPROVE", "owner", 1L, "", null, now, now, null);
    private final CopilotCommandRecoveryDTO checkpoint = new CopilotCommandRecoveryDTO(binding, new CommandEvidenceDTO(3, "unused"));

    @Test void nonzeroExitIsANormalCommandResultWithBoundedUnicodeLogs() {
        var source = source("EXITED", "UNCHANGED", "SUCCEEDED");
        var result = formatter.format(checkpoint, source);
        assertThat(result.code()).isEqualTo("COMMAND_RESULT");
        assertThat(result.result().exitCode()).isEqualTo(7);
        assertThat(result.result().state()).isEqualTo("EXITED");
        assertThat(result.logTruncated()).isTrue();
        String excerpt = result.result().logs().getFirst().text();
        assertThat(excerpt).doesNotContain("\uFFFD");
        assertThat(excerpt.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16384);
        assertThat(source.logs().getFirst().text()).hasSize(40000);
    }
    @Test void pendingSynchronizationOrCleanupCannotCompleteTheParent() {
        assertThatThrownBy(() -> formatter.format(checkpoint, source("EXITED", "PENDING", "SUCCEEDED"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> formatter.format(checkpoint, source("STOPPED", "UNCHANGED", "PENDING"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> formatter.format(checkpoint, source("PASSED", "UNCHANGED", "SUCCEEDED"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void syncConflictIsExplicitAndDifferentCheckpointVersionsAreRejected() {
        assertThat(formatter.format(checkpoint, source("TIMED_OUT", "CONFLICT", "SUCCEEDED")).code()).isEqualTo("COMMAND_SYNC_FAILED");
        assertThatThrownBy(() -> formatter.format(new CopilotCommandRecoveryDTO(binding, new CommandEvidenceDTO(2, "old")), source("EXITED", "UNCHANGED", "SUCCEEDED")))
            .isInstanceOf(IllegalStateException.class);
    }
    private CommandTaskResponse source(String state, String sync, String cleanup) {
        return new CommandTaskResponse("child", "exercise", "execution", "workspace", "1", "exit 7", ".", state, "command_exit", false, true,
            sync, "", null, false, "EXITED".equals(state) ? 7 : null, List.of(new TestLogResponse("stdout", "汉字".repeat(20000))),
            "0", false, cleanup, cleanup, "3", now, now);
    }
}
