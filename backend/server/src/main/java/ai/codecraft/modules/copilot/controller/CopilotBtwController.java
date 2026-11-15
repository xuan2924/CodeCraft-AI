package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotBtwRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotBtwResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.service.CopilotBtwService;
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
public class CopilotBtwController {
    private final CopilotBtwService btw;
    public CopilotBtwController(CopilotBtwService btw) { this.btw = btw; }
    @GetMapping("/api/exercises/{exercise}/copilot/conversations/{parent}/btw")
    public ApiResponse<CopilotBtwResponse> find(@PathVariable UUID exercise, @PathVariable UUID parent, HttpServletRequest request) {
        return ApiResponse.success(btw.find(IdentityHttp.sessionHash(request), exercise.toString(), parent.toString()));
    }
    @PostMapping("/api/exercises/{exercise}/copilot/conversations/{parent}/btw/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CopilotTaskResponse> start(@PathVariable UUID exercise, @PathVariable UUID parent, @RequestBody CopilotBtwRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(btw.start(IdentityHttp.sessionHash(request), exercise.toString(), parent.toString(), credential, key, input));
    }
}
