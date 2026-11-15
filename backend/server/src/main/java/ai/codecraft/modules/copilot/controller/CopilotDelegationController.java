package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationResponse;
import ai.codecraft.modules.copilot.service.CopilotDelegationService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotDelegationController {
    private final CopilotDelegationService delegations;
    public CopilotDelegationController(CopilotDelegationService delegations) { this.delegations = delegations; }

    @GetMapping("/api/copilot-tasks/{parent}/delegations")
    public ApiResponse<List<CopilotDelegationResponse>> list(@PathVariable UUID parent, HttpServletRequest request) {
        return ApiResponse.success(delegations.list(IdentityHttp.sessionHash(request), parent.toString()));
    }
}
