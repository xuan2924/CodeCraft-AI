package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.service.TestTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.RequestParam;
import ai.codecraft.modules.workspace.stream.TaskEventTransport;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestTaskController {
    private final TestTaskService tasks;
    private final TaskEventTransport streams;
    public TestTaskController(TestTaskService tasks, TaskEventTransport streams) { this.tasks = tasks; this.streams = streams; }

    @GetMapping(value = "/api/exercises/{id}/tasks/{taskId}/events", produces = "text/event-stream")
    public void events(@PathVariable UUID id, @PathVariable UUID taskId,
        @RequestParam(value = "after", required = false) String after,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent,
        HttpServletRequest request, HttpServletResponse response) throws IOException {
        streams.open(IdentityHttp.sessionHash(request), id.toString(), taskId.toString(), after, lastEvent, request, response);
    }

    @PostMapping("/api/exercises/{id}/tests")
    public ResponseEntity<ApiResponse<TestTaskResponse>> start(@PathVariable UUID id, @RequestBody StartTestRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.start(IdentityHttp.sessionHash(request), id.toString(), key, credential, input)));
    }

    @PostMapping("/api/exercises/{id}/tasks/{taskId}/stop")
    public ResponseEntity<ApiResponse<TestTaskResponse>> stop(@PathVariable UUID id, @PathVariable UUID taskId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        TestTaskResponse result = tasks.stop(IdentityHttp.sessionHash(request), id.toString(), taskId.toString(), key, credential);
        return ResponseEntity.status(result.finishedAt() == null ? 202 : 200).header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }

    @GetMapping("/api/exercises/{id}/tasks/{taskId}")
    public ApiResponse<TestTaskResponse> query(@PathVariable UUID id, @PathVariable UUID taskId, HttpServletRequest request) {
        return ApiResponse.success(tasks.query(IdentityHttp.sessionHash(request), id.toString(), taskId.toString()));
    }

    @GetMapping("/api/exercises/{id}/tasks")
    public ApiResponse<List<TestTaskResponse>> recent(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(tasks.recent(IdentityHttp.sessionHash(request), id.toString()));
    }
}
