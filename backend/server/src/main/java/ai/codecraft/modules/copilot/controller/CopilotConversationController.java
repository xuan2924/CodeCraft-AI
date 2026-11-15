package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotForkRequest;
import org.springframework.web.bind.annotation.RequestBody;
import ai.codecraft.modules.copilot.service.CopilotConversationService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotConversationController {
    private final CopilotConversationService conversations;
    public CopilotConversationController(CopilotConversationService conversations) { this.conversations = conversations; }
    @GetMapping("/api/exercises/{exercise}/copilot/conversations")
    public ApiResponse<List<CopilotConversationResponse>> list(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(conversations.list(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @PostMapping("/api/exercises/{exercise}/copilot/conversations/fork")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CopilotConversationResponse> fork(@PathVariable UUID exercise, @RequestBody CopilotForkRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(conversations.fork(IdentityHttp.sessionHash(request), exercise.toString(), credential, key, input));
    }
    @PostMapping("/api/exercises/{exercise}/copilot/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CopilotConversationResponse> create(@PathVariable UUID exercise,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(conversations.create(IdentityHttp.sessionHash(request), exercise.toString(), credential, key));
    }
}
