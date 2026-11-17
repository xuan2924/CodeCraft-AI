package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.service.TerminalService;
import ai.codecraft.modules.workspace.service.TerminalStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TerminalController {
    private final TerminalService terminals;
    public TerminalController(TerminalService terminals){this.terminals=terminals;}
    public record OpenRequest(String expectedRevision) { }
    public record SyncRequest(UUID commandId) { }
    @PostMapping("/api/exercises/{id}/terminal/session")
    public ResponseEntity<ApiResponse<TerminalStore.SessionResponse>> session(@PathVariable UUID id,@RequestBody OpenRequest input,
        @RequestHeader(value="X-Operation-Credential",required=false) String credential,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(ApiResponse.success(
            terminals.issue(IdentityHttp.sessionHash(request),id.toString(),credential,key,input.expectedRevision())));
    }
    @GetMapping("/api/exercises/{id}/terminal/sessions")
    public ResponseEntity<ApiResponse<java.util.List<ai.codecraft.modules.workspace.model.dto.TerminalAuditResponse>>> history(@PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(terminals.history(IdentityHttp.sessionHash(request),id.toString())));
    }
    @PostMapping("/api/exercises/{id}/terminal/sync")
    public ResponseEntity<ApiResponse<CommandTaskResponse>> sync(@PathVariable UUID id,@RequestBody SyncRequest input,HttpServletRequest request){
        if(input.commandId()==null)throw new ai.codecraft.common.exception.BusinessException(ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode.INVALID_REQUEST);
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(ApiResponse.success(
            terminals.sync(IdentityHttp.sessionHash(request),id.toString(),input.commandId().toString())));
    }
}
