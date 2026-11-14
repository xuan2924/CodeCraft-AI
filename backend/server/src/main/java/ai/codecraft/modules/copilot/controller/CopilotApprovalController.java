package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalChangeResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeResponse;
import ai.codecraft.modules.copilot.service.CopilotApprovalModeService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotApprovalController {
    private final CopilotApprovalModeService modes;
    public CopilotApprovalController(CopilotApprovalModeService modes) { this.modes = modes; }

    @GetMapping("/api/exercises/{exercise}/copilot/approval-mode")
    public ApiResponse<CopilotApprovalModeResponse> current(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(modes.current(IdentityHttp.sessionHash(request), exercise.toString()));
    }

    @PostMapping("/api/exercises/{exercise}/copilot/approval-mode")
    public ApiResponse<CopilotApprovalChangeResponse> change(@PathVariable UUID exercise, @RequestBody CopilotApprovalModeRequest input,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(modes.change(IdentityHttp.sessionHash(request), exercise.toString(), credential, key, input));
    }
}
