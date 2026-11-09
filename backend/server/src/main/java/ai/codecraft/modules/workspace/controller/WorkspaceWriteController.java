package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.ControlResponse;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.service.WorkspaceWriteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceWriteController {
    private final WorkspaceWriteService writes;
    public WorkspaceWriteController(WorkspaceWriteService writes) { this.writes = writes; }

    @GetMapping("/api/exercises/{id}/control")
    public ApiResponse<ControlResponse> control(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(writes.control(IdentityHttp.sessionHash(request), id.toString()));
    }

    @PostMapping("/api/exercises/{id}/control/{action:acquire|takeover|release}")
    public ResponseEntity<ApiResponse<ControlResponse>> control(@PathVariable UUID id, @PathVariable String action,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        String kind = switch (action) {
            case "acquire" -> "ACQUIRE";
            case "takeover" -> "TAKEOVER";
            default -> "RELEASE";
        };
        ControlResponse result = writes.control(IdentityHttp.sessionHash(request), id.toString(), kind, key, credential);
        return ResponseEntity.status(result.state().equals("SUCCEEDED") ? 200 : 202).header("Cache-Control", "no-store")
            .body(ApiResponse.success(result));
    }

    @PostMapping("/api/exercises/{id}/file-batches")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> batch(@PathVariable UUID id, @RequestBody FileBatchRequest batch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        WorkspaceOperationResponse result = writes.batch(IdentityHttp.sessionHash(request), id.toString(), key, credential, batch);
        return ResponseEntity.status(result.state().equals("SUCCEEDED") ? 200 : 202).body(ApiResponse.success(result));
    }
}
