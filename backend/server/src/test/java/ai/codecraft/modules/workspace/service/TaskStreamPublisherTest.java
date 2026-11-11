package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.entity.TaskEventEntity;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskStreamPublisherTest {
    @Test
    void preservesOrderedLogsAndTerminalEvidenceAcrossBoundedDatabaseBatches() throws Exception {
        TaskStreamRepository database = mock(TaskStreamRepository.class);
        when(database.find("task")).thenReturn(new TaskStreamEntity("task", "stream", 0, 1));
        List<TaskEventEntity> stored = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        doAnswer(call -> {
            List<TaskEventEntity> events = call.getArgument(1);
            sizes.add(events.size());
            stored.addAll(events);
            return null;
        }).when(database).appendBatch(eq("task"), anyList());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ObjectMapper json = new ObjectMapper();
        TaskStreamPublisher publisher = new TaskStreamPublisher(database, json, Clock.fixed(now, ZoneOffset.UTC));
        String output = "x".repeat(65536) + "最后一行😀";
        publisher.checkpoint(snapshot(List.of(), false), snapshot(List.of(new TestLogResponse("stderr", output)), true));
        assertThat(sizes).containsExactly(64, 4);
        assertThat(stored).hasSize(68);
        assertThat(stored.getFirst().type()).isEqualTo("progress");
        assertThat(stored.get(66).type()).isEqualTo("log_truncated");
        assertThat(stored.getLast().type()).isEqualTo("task_result");
        StringBuilder retained = new StringBuilder();
        for (int index = 0; index < stored.size(); index++) {
            TaskEventEntity event = stored.get(index);
            assertThat(event.sequence()).isEqualTo(index + 1);
            var envelope = json.readTree(event.payload());
            assertThat(envelope.path("sequence").asText()).isEqualTo(Long.toString(event.sequence()));
            assertThat(envelope.path("occurredAt").asText()).isEqualTo(now.toString());
            if (event.type().equals("log")) {
                assertThat(envelope.path("payload").path("channel").asText()).isEqualTo("stderr");
                retained.append(envelope.path("payload").path("data").asText());
            }
        }
        assertThat(retained.toString()).isEqualTo(output);
    }

    private TestTaskResponse snapshot(List<TestLogResponse> logs, boolean finished) {
        return new TestTaskResponse("task", "exercise", "execution", "workspace", "1", "base",
            finished ? "STOPPED" : "RUNNING", finished ? "user_stop" : "", null,
            finished ? Instant.parse("2026-01-01T00:00:00Z") : null, null, finished, "PENDING", "PENDING",
            finished ? "100" : "0", false, logs, null, null);
    }
}
