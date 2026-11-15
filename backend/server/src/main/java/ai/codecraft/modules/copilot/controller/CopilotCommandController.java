package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandsResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.service.CopilotCommandService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotCommandController {
    private final CopilotCommandService commands;
    public CopilotCommandController(CopilotCommandService commands) { this.commands = commands; }

    @GetMapping("/api/copilot-tasks/{task}/commands")
    public ApiResponse<CopilotCommandsResponse> recent(@PathVariable UUID task, @RequestParam(defaultValue = "0") long before, HttpServletRequest request) {
        return ApiResponse.success(commands.recent(IdentityHttp.sessionHash(request), task.toString(), before));
    }
    @GetMapping("/api/copilot-commands/{command}")
    public ApiResponse<CopilotCommandResponse> find(@PathVariable UUID command, HttpServletRequest request) {
        return ApiResponse.success(commands.read(IdentityHttp.sessionHash(request), command.toString()));
    }
    @PostMapping("/api/copilot-commands/{command}/decision")
    public ApiResponse<CopilotCommandResponse> decide(@PathVariable UUID command, @RequestBody CopilotProposalDecisionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(commands.decide(IdentityHttp.sessionHash(request), command.toString(), credential, key, input));
    }
}
