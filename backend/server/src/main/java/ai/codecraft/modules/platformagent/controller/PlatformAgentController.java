package ai.codecraft.modules.platformagent.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.platformagent.model.dto.PlatformActionRequest;
import ai.codecraft.modules.platformagent.model.dto.PlatformCatalogResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformCheckResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.platformagent.model.dto.PlatformVersionResponse;
import ai.codecraft.modules.platformagent.service.PlatformAgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/platform-agents")
public class PlatformAgentController {
    private final PlatformAgentService service;
    public PlatformAgentController(PlatformAgentService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PlatformCatalogResponse> list(@RequestParam(required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(service.list(IdentityHttp.sessionHash(request), after));
    }

    @GetMapping("/{id}")
    public ApiResponse<PlatformVersionResponse> get(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.get(IdentityHttp.sessionHash(request), id.toString()));
    }

    @PostMapping
    public ApiResponse<PlatformVersionResponse> create(@RequestBody PlatformDraftRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(service.create(IdentityHttp.sessionHash(request), key, input));
    }

    @PostMapping("/{id}/{action:publish|retire|credential}")
    public ApiResponse<PlatformVersionResponse> change(@PathVariable UUID id, @PathVariable String action, @RequestBody PlatformActionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        String kind = switch (action) {
            case "publish" -> "PUBLISH";
            case "retire" -> "RETIRE";
            default -> "KEY";
        };
        return ApiResponse.success(service.change(IdentityHttp.sessionHash(request), id.toString(), kind, key, input));
    }

    @PostMapping("/{id}/checks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PlatformCheckResponse> check(@PathVariable UUID id, @RequestBody PlatformActionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(service.check(IdentityHttp.sessionHash(request), id.toString(), key, input));
    }

    @GetMapping("/{id}/checks/latest")
    public ApiResponse<PlatformCheckResponse> latest(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.latest(IdentityHttp.sessionHash(request), id.toString()));
    }
}
