package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryPreviewResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreRequest;
import ai.codecraft.modules.workspace.service.WorkspaceHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceHistoryController {
    private final WorkspaceHistoryService history;
    public WorkspaceHistoryController(WorkspaceHistoryService history) { this.history = history; }

    @GetMapping("/api/exercises/{id}/history")
    public ResponseEntity<ApiResponse<WorkspaceHistoryResponse>> list(@PathVariable UUID id,
        @RequestParam(value = "before", defaultValue = "0") long before, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(
            history.recent(IdentityHttp.sessionHash(request), id.toString(), before)));
    }

    @GetMapping("/api/exercises/{id}/history/{change}/preview")
    public ResponseEntity<ApiResponse<WorkspaceHistoryPreviewResponse>> preview(@PathVariable UUID id, @PathVariable UUID change,
        @RequestParam("revision") String revision, @RequestParam(value = "mode", defaultValue = "DIFF") String mode,
        @RequestParam(value = "targetRevision", required = false) String targetRevision, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(
            history.preview(IdentityHttp.sessionHash(request), id.toString(), change.toString(), revision, mode, targetRevision)));
    }

    @PostMapping("/api/exercises/{id}/history/restore")
    public ResponseEntity<ApiResponse<WorkspaceRestoreResponse>> restore(@PathVariable UUID id, @RequestBody WorkspaceRestoreRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        var result = history.restore(IdentityHttp.sessionHash(request), id.toString(), key, credential, input);
        return ResponseEntity.status(result.notAcceptedReason() == null ? 202 : 200).header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }
}
