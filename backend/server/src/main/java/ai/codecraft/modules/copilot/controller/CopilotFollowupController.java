package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResumeRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResumeResponse;
import ai.codecraft.modules.copilot.service.CopilotFollowupService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exercises/{exercise}/copilot/conversations/{conversation}/followups")
public class CopilotFollowupController {
    private final CopilotFollowupService queue;
    public CopilotFollowupController(CopilotFollowupService queue) { this.queue = queue; }
    @GetMapping
    public ApiResponse<List<CopilotFollowupResponse>> recent(@PathVariable UUID exercise, @PathVariable UUID conversation, HttpServletRequest request) {
        return ApiResponse.success(queue.recent(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString()));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CopilotFollowupResponse> enqueue(@PathVariable UUID exercise, @PathVariable UUID conversation, @RequestBody CopilotFollowupRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(queue.enqueue(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString(), credential, key, input));
    }
    @PostMapping("/{item}/cancel")
    public ApiResponse<CopilotFollowupResponse> cancel(@PathVariable UUID exercise, @PathVariable UUID conversation, @PathVariable UUID item, HttpServletRequest request) {
        return ApiResponse.success(queue.cancel(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString(), item.toString()));
    }
    @PostMapping("/resume")
    public ApiResponse<CopilotFollowupResumeResponse> resume(@PathVariable UUID exercise, @PathVariable UUID conversation, @RequestBody CopilotFollowupResumeRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(queue.resume(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString(), credential, key, input.feeAcknowledged()));
    }
}
