package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.DeletionResponse;
import ai.codecraft.modules.workspace.service.DeletionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.List;
import ai.codecraft.modules.workspace.model.dto.ExerciseHistoryResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeletionController {
    private final DeletionStore deletions;
    public DeletionController(DeletionStore deletions) { this.deletions = deletions; }

    @GetMapping("/api/exercises")
    public ApiResponse<List<ExerciseHistoryResponse>> history(@RequestParam(required = false) UUID after, HttpServletRequest request) {
        return ApiResponse.success(deletions.history(IdentityHttp.sessionHash(request), after == null ? null : after.toString()));
    }

    @DeleteMapping("/api/exercises/{id}")
    public ResponseEntity<ApiResponse<DeletionResponse>> delete(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(deletions.request(IdentityHttp.sessionHash(request), id.toString(), key)));
    }
    @GetMapping("/api/exercises/{id}/deletion")
    public ApiResponse<DeletionResponse> status(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(deletions.status(IdentityHttp.sessionHash(request), id.toString()));
    }
    @PostMapping("/api/exercises/{id}/deletion/retry")
    public ResponseEntity<ApiResponse<DeletionResponse>> retry(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(deletions.retry(IdentityHttp.sessionHash(request), id.toString(), key)));
    }
}
