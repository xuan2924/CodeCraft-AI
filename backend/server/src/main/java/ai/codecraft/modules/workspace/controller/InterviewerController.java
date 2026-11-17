package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.InterviewerRequest;
import ai.codecraft.modules.workspace.model.dto.InterviewerTaskResponse;
import ai.codecraft.modules.workspace.service.InterviewerService;
import ai.codecraft.modules.workspace.stream.TaskEventTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
public class InterviewerController {
    private final InterviewerService interviewer;
    private final TaskEventTransport transport;
    public InterviewerController(InterviewerService interviewer, TaskEventTransport transport) { this.interviewer = interviewer; this.transport = transport; }
    @PostMapping("/api/exercises/{exercise}/interviewer/tasks")
    public ResponseEntity<ApiResponse<InterviewerTaskResponse>> ask(@PathVariable UUID exercise, @RequestBody InterviewerRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(interviewer.ask(IdentityHttp.sessionHash(request), exercise.toString(), key, credential, input)));
    }
    @GetMapping("/api/exercises/{exercise}/interviewer/tasks")
    public ApiResponse<List<InterviewerTaskResponse>> history(@PathVariable UUID exercise, @RequestParam(required = false) UUID before, HttpServletRequest request) {
        return ApiResponse.success(interviewer.history(IdentityHttp.sessionHash(request), exercise.toString(), before == null ? null : before.toString()));
    }
    @GetMapping("/api/exercises/{exercise}/interviewer/tasks/{task}")
    public ApiResponse<InterviewerTaskResponse> find(@PathVariable UUID exercise, @PathVariable UUID task, HttpServletRequest request) {
        return ApiResponse.success(interviewer.find(IdentityHttp.sessionHash(request), exercise.toString(), task.toString()));
    }
    @GetMapping(value = "/api/exercises/{exercise}/interviewer/tasks/{task}/events", produces = "text/event-stream")
    public void events(@PathVariable UUID exercise, @PathVariable UUID task, @RequestParam(required = false) String after,
        @RequestHeader(value = "Last-Event-ID", required = false) String last, HttpServletRequest request, HttpServletResponse response) throws IOException {
        transport.open(IdentityHttp.sessionHash(request), exercise.toString(), task.toString(), after, last, request, response, interviewer::batch);
    }
}
