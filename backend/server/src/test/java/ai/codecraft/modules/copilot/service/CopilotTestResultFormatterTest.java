package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CopilotTestResultFormatterTest {
    private final CopilotTestResultFormatter formatter = new CopilotTestResultFormatter();

    @Test void failuresAreNormalVersionBoundResultsAndKeepUnderlyingLogLossSeparate() {
        var source = result("FAILED", true, List.of(new TestLogResponse("stderr", "expected 42, got 0")));
        var projection = formatter.format(source, false);
        assertThat(projection.code()).isEqualTo("TEST_RESULT");
        assertThat(projection.result().state()).isEqualTo("FAILED");
        assertThat(projection.result().revision()).isEqualTo("7");
        assertThat(projection.result().taskId()).isEqualTo("test");
        assertThat(projection.result().logsIncomplete()).isTrue();
        assertThat(projection.result().droppedBytes()).isEqualTo("100");
        assertThat(projection.logTruncated()).isFalse();
        assertThat(projection.omittedLogBytes()).isEqualTo("0");
    }
    @Test void truncatesTheTailAtUtf8BoundariesAndPreservesChannelOrder() {
        String large = "中文🙂".repeat(5000);
        var projection = formatter.format(result("PASSED", true, List.of(new TestLogResponse("stdout", large), new TestLogResponse("stderr", "last failure detail"))), true);
        assertThat(projection.reused()).isTrue();
        assertThat(projection.logTruncated()).isTrue();
        assertThat(projection.result().logs().getLast().text()).isEqualTo("last failure detail");
        assertThat(projection.result().logs().getFirst().channel()).isEqualTo("stdout");
        assertThat(projection.result().logs().stream().mapToInt(value -> value.text().getBytes(StandardCharsets.UTF_8).length).sum()).isLessThanOrEqualTo(16384);
        assertThat(projection.result().logs().getFirst().text()).doesNotContain("�");
        assertThat(Long.parseLong(projection.omittedLogBytes())).isPositive();
    }
    @Test void boundsEmptyLogSegmentsAndRefusesUnconfirmedExecution() {
        var empty = IntStream.range(0, 1000).mapToObj(index -> new TestLogResponse("stdout", "")).toList();
        assertThat(formatter.format(result("STOPPED", true, empty), false).result().logs()).hasSize(64);
        assertThat(formatter.format(result("STOPPED", true, empty), false).logTruncated()).isTrue();
        assertThatThrownBy(() -> formatter.format(result("INTERRUPTED", false, List.of()), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> formatter.format(result("RUNNING", true, List.of()), false)).isInstanceOf(IllegalStateException.class);
    }
    private TestTaskResponse result(String state, boolean quiescent, List<TestLogResponse> logs) {
        return new TestTaskResponse("test", "exercise", "execution", "workspace", "7", "base", state, "test_result", Instant.EPOCH, Instant.EPOCH.plusSeconds(2),
            1, quiescent, "SUCCEEDED", "SUCCEEDED", "100", true, logs, null, null);
    }
}
