package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotSteersResponse;
import ai.codecraft.modules.copilot.service.CopilotSteerService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotSteerController {
    private final CopilotSteerService steers;
    public CopilotSteerController(CopilotSteerService steers) { this.steers = steers; }
    @GetMapping("/api/copilot-tasks/{task}/steers")
    public ApiResponse<CopilotSteersResponse> list(@PathVariable UUID task, @RequestParam(defaultValue = "0") long before, HttpServletRequest request) {
        return ApiResponse.success(steers.list(IdentityHttp.sessionHash(request), task.toString(), before));
    }
    @PostMapping("/api/copilot-tasks/{task}/steers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CopilotSteerResponse> accept(@PathVariable UUID task, @RequestBody CopilotSteerRequest input,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(steers.accept(IdentityHttp.sessionHash(request), task.toString(), credential, key, input));
    }
}
