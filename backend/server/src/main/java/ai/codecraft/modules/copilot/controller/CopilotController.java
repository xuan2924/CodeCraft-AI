package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotMessagesResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.copilot.service.CopilotProposalService;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDetailResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalsResponse;
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
import ai.codecraft.modules.copilot.service.CopilotTestTools;
import ai.codecraft.modules.copilot.model.dto.CopilotTestResponse;
import java.util.List;

@RestController
public class CopilotController {
    private final CopilotTaskService tasks;
    private final TaskEventTransport transport;
    private final CopilotProposalService proposals;
    private final CopilotTestTools tests;
    public CopilotController(CopilotTaskService tasks, TaskEventTransport transport, CopilotProposalService proposals, CopilotTestTools tests) {
        this.tasks = tasks; this.transport = transport; this.proposals = proposals; this.tests = tests;
    }

    @GetMapping("/api/copilot-tasks/{task}/tests")
    public ApiResponse<List<CopilotTestResponse>> tests(@PathVariable UUID task, HttpServletRequest request) {
        return ApiResponse.success(tests.recent(IdentityHttp.sessionHash(request), task.toString()));
    }

    @GetMapping("/api/copilot-tasks/{task}/proposals")
    public ApiResponse<CopilotProposalsResponse> proposals(@PathVariable UUID task, @RequestParam(defaultValue = "0") long before, HttpServletRequest request) {
        return ApiResponse.success(proposals.list(IdentityHttp.sessionHash(request), task.toString(), before));
    }
    @GetMapping("/api/copilot-proposals/{proposal}")
    public ApiResponse<CopilotProposalDetailResponse> proposal(@PathVariable UUID proposal, HttpServletRequest request) {
        return ApiResponse.success(proposals.detail(IdentityHttp.sessionHash(request), proposal.toString()));
    }
    @GetMapping("/api/copilot-proposals/{proposal}/status")
    public ApiResponse<CopilotProposalResponse> proposalStatus(@PathVariable UUID proposal, HttpServletRequest request) {
        return ApiResponse.success(proposals.summary(IdentityHttp.sessionHash(request), proposal.toString()));
    }
    @PostMapping("/api/copilot-proposals/{proposal}/decision")
    public ApiResponse<CopilotProposalResponse> decide(@PathVariable UUID proposal, @RequestBody CopilotProposalDecisionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(proposals.decide(IdentityHttp.sessionHash(request), proposal.toString(), credential, key, input));
    }

    @PostMapping("/api/exercises/{exercise}/copilot/tasks")
    public ResponseEntity<ApiResponse<CopilotTaskResponse>> start(@PathVariable UUID exercise, @RequestBody CopilotStartRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(tasks.start(IdentityHttp.sessionHash(request), exercise.toString(), credential, key, input)));
    }
    @GetMapping("/api/exercises/{exercise}/copilot/tasks/latest")
    public ApiResponse<CopilotDeliveryDTO> latest(@PathVariable UUID exercise, @RequestParam(required = false) UUID conversation, HttpServletRequest request) {
        return ApiResponse.success(conversation == null ? tasks.latest(IdentityHttp.sessionHash(request), exercise.toString())
            : tasks.latestInConversation(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString()));
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
        @RequestParam(defaultValue = "-1") long after, @RequestParam(required = false) UUID conversation, HttpServletRequest request) {
        return ApiResponse.success(conversation == null ? tasks.messages(IdentityHttp.sessionHash(request), exercise.toString(), after)
            : tasks.messagesInConversation(IdentityHttp.sessionHash(request), exercise.toString(), after, conversation.toString()));
    }
    @GetMapping(value = "/api/exercises/{exercise}/copilot/tasks/{task}/events", produces = "text/event-stream")
    public void events(@PathVariable UUID exercise, @PathVariable UUID task, @RequestParam(required = false) String after,
        @RequestHeader(value = "Last-Event-ID", required = false) String last, HttpServletRequest request, HttpServletResponse response) throws IOException {
        transport.open(IdentityHttp.sessionHash(request), exercise.toString(), task.toString(), after, last, request, response, tasks::batch);
    }
}
