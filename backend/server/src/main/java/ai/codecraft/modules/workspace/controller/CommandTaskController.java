package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.CommandChangesResponse;
import ai.codecraft.modules.workspace.model.dto.CommandHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.service.CommandTaskService;
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
public class CommandTaskController {
    private final CommandTaskService tasks;
    public CommandTaskController(CommandTaskService tasks) { this.tasks = tasks; }

    @PostMapping("/api/exercises/{id}/commands")
    public ResponseEntity<ApiResponse<CommandTaskResponse>> start(@PathVariable UUID id, @RequestBody CommandStartRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.start(IdentityHttp.sessionHash(request), id.toString(), key, credential, input)));
    }

    @GetMapping("/api/exercises/{id}/commands")
    public ResponseEntity<ApiResponse<CommandHistoryResponse>> history(@PathVariable UUID id,
        @RequestParam(value = "after", required = false) UUID after, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.history(IdentityHttp.sessionHash(request), id.toString(), after == null ? null : after.toString())));
    }

    @GetMapping("/api/exercises/{id}/commands/{commandId}")
    public ResponseEntity<ApiResponse<CommandTaskResponse>> query(@PathVariable UUID id, @PathVariable UUID commandId, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.query(IdentityHttp.sessionHash(request), id.toString(), commandId.toString())));
    }

    @PostMapping("/api/exercises/{id}/commands/{commandId}/stop")
    public ResponseEntity<ApiResponse<CommandTaskResponse>> stop(@PathVariable UUID id, @PathVariable UUID commandId,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.stop(IdentityHttp.sessionHash(request), id.toString(), commandId.toString(), credential)));
    }

    @GetMapping("/api/exercises/{id}/commands/{commandId}/changes")
    public ResponseEntity<ApiResponse<CommandChangesResponse>> changes(@PathVariable UUID id, @PathVariable UUID commandId, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(
            tasks.changes(IdentityHttp.sessionHash(request), id.toString(), commandId.toString())));
    }
}
