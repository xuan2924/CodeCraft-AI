package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotMessagesResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.stream.TaskEventTransport;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotController {
    private final CopilotTaskService tasks;
    private final TaskEventTransport transport;
    public CopilotController(CopilotTaskService tasks, TaskEventTransport transport) { this.tasks = tasks; this.transport = transport; }

    @PostMapping("/api/exercises/{exercise}/copilot/tasks")
    public ResponseEntity<ApiResponse<CopilotTaskResponse>> start(@PathVariable UUID exercise, @RequestBody CopilotStartRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(tasks.start(IdentityHttp.sessionHash(request), exercise.toString(), credential, key, input)));
    }
    @GetMapping("/api/exercises/{exercise}/copilot/tasks/latest")
    public ApiResponse<CopilotDeliveryDTO> latest(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(tasks.latest(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @GetMapping("/api/copilot-tasks/{task}")
    public ApiResponse<CopilotTaskResponse> find(@PathVariable UUID task, HttpServletRequest request) {
        return ApiResponse.success(tasks.find(IdentityHttp.sessionHash(request), task.toString()));
    }
    @PostMapping("/api/copilot-tasks/{task}/stop")
    public ApiResponse<CopilotTaskResponse> stop(@PathVariable UUID task,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(tasks.stop(IdentityHttp.sessionHash(request), task.toString(), key));
    }
    @GetMapping("/api/exercises/{exercise}/copilot/messages")
    public ApiResponse<CopilotMessagesResponse> messages(@PathVariable UUID exercise,
        @RequestParam(defaultValue = "-1") long after, HttpServletRequest request) {
        return ApiResponse.success(tasks.messages(IdentityHttp.sessionHash(request), exercise.toString(), after));
    }
    @GetMapping(value = "/api/exercises/{exercise}/copilot/tasks/{task}/events", produces = "text/event-stream")
    public void events(@PathVariable UUID exercise, @PathVariable UUID task, @RequestParam(required = false) String after,
        @RequestHeader(value = "Last-Event-ID", required = false) String last, HttpServletRequest request, HttpServletResponse response) throws IOException {
        transport.open(IdentityHttp.sessionHash(request), exercise.toString(), task.toString(), after, last, request, response, tasks::batch);
    }
}
