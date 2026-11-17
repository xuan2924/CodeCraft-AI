package ai.codecraft.modules.workspace.service;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import ai.codecraft.modules.workspace.model.entity.TaskEventEntity;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invoked under the task lock, in the snapshot transaction; no network effects. */
@Service
public class TaskStreamPublisher {
    private final TaskStreamRepository streams;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransientTaskStreamHub notifications;
    public TaskStreamPublisher(TaskStreamRepository streams, ObjectMapper json, Clock clock, TransientTaskStreamHub notifications) {
        this.streams = streams; this.json = json; this.clock = clock; this.notifications = notifications;
    }
    @Transactional
    public void checkpoint(TestTaskResponse before, TestTaskResponse after) {
        if (before.equals(after)) return;
        TaskStreamEntity stream = streams.find(after.taskId());
        long sequence = stream.sequence();
        List<TaskEventEntity> batch = new ArrayList<>();
        boolean replace = !before.logs().equals(after.logs());
        sequence = append(batch, stream, after, sequence, "progress", Map.of("state", after.state(), "replaceLogs", replace));
        if (replace) {
            for (TestLogResponse log : after.logs()) {
                String data = log.text();
                // A chunk has at most 4096 UTF-8 bytes, even for surrogate pairs;
                // JSON escaping plus envelope remains below a 64 KiB batch.
                for (int start = 0; start < data.length();) {
                    int end = Math.min(start + 1024, data.length());
                    if (end < data.length() && Character.isHighSurrogate(data.charAt(end - 1))) end--;
                    sequence = append(batch, stream, after, sequence, "log", Map.of("channel", log.channel(), "data", data.substring(start, end)));
                    start = end;
                }
            }
        }
        if (after.logsIncomplete() || !after.droppedBytes().equals("0")) {
            sequence = append(batch, stream, after, sequence, "log_truncated", Map.of("droppedBytes", after.droppedBytes(), "policy", "tail"));
        }
        if (after.finishedAt() != null) sequence = append(batch, stream, after, sequence, "task_result", Map.of("state", after.state(), "reason", after.reason()));
        if (!batch.isEmpty()) streams.appendBatch(after.taskId(), batch);
        streams.advance(after.taskId(), sequence);
        streams.trim(after.taskId());
        streams.retained(after.taskId());
        notifications.afterCommit(after.taskId());
    }
    private long append(List<TaskEventEntity> batch, TaskStreamEntity stream, TestTaskResponse task, long previous, String type, Map<String, Object> payload) {
        long sequence = Math.addExact(previous, 1);
        try {
            String encoded = json.writeValueAsString(Map.of("schemaVersion", 1, "taskId", task.taskId(),
                "executionInstanceId", task.executionInstanceId(), "streamId", stream.streamId(), "sequence", Long.toString(sequence),
                "type", type, "occurredAt", clock.instant().toString(), "payload", payload));
            batch.add(new TaskEventEntity(sequence, type, encoded, encoded.getBytes(StandardCharsets.UTF_8).length));
            if (batch.size() == 64) {
                streams.appendBatch(task.taskId(), batch);
                batch.clear();
            }
            return sequence;
        } catch (JsonProcessingException error) { throw new IllegalStateException("Cannot serialize task event", error); }
    }
}
