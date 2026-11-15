package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotContextResponse;
import ai.codecraft.modules.copilot.service.CopilotCompactionService;
import ai.codecraft.modules.copilot.service.CopilotUsageService;
import ai.codecraft.modules.copilot.model.dto.CopilotUsageResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotContextController {
    private final CopilotCompactionService compactions;
    private final CopilotUsageService usage;
    public CopilotContextController(CopilotCompactionService compactions, CopilotUsageService usage) { this.compactions = compactions; this.usage = usage; }
    @GetMapping("/api/exercises/{exercise}/copilot/usage")
    public ApiResponse<CopilotUsageResponse> usage(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(usage.current(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @GetMapping("/api/exercises/{exercise}/copilot/context")
    public ApiResponse<CopilotContextResponse> current(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(compactions.current(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @PostMapping("/api/exercises/{exercise}/copilot/compactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CopilotCompactionResponse> accept(@PathVariable UUID exercise, @RequestBody CopilotCompactionRequest input,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(compactions.accept(IdentityHttp.sessionHash(request), exercise.toString(), credential, key, input));
    }
    @PostMapping("/api/exercises/{exercise}/copilot/compactions/{id}/stop")
    public ApiResponse<CopilotCompactionResponse> cancel(@PathVariable UUID exercise, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(compactions.cancel(IdentityHttp.sessionHash(request), exercise.toString(), id.toString()));
    }
}
