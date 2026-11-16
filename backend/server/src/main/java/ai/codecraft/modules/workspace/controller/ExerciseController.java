package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.CreateExerciseRequest;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.service.ExerciseService;
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
public class ExerciseController {
    private final ExerciseService exercises;

    public ExerciseController(ExerciseService exercises) { this.exercises = exercises; }

    @PostMapping("/api/exercises")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> create(@RequestBody CreateExerciseRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        String version = input.problemVersionId() == null ? null : input.problemVersionId().toString();
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.create(IdentityHttp.sessionHash(request), version, key)));
    }

    @PostMapping("/api/exercises/{id}/start")
    public ApiResponse<ExerciseResponse> start(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(exercises.start(IdentityHttp.sessionHash(request), id.toString(), key, credential));
    }

    @PostMapping("/api/exercises/{id}/pause")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> pause(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.pause(IdentityHttp.sessionHash(request), id.toString(), key, credential, false)));
    }

    @PostMapping("/api/exercises/{id}/resume")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> resume(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.pause(IdentityHttp.sessionHash(request), id.toString(), key, credential, true)));
    }

    @PostMapping("/api/exercises/{id}/end")
    public ResponseEntity<ApiResponse<ExerciseResponse>> end(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.end(IdentityHttp.sessionHash(request), id.toString(), key, credential)));
    }

    @PostMapping("/api/exercises/{id}/recovery-end")
    public ResponseEntity<ApiResponse<ExerciseResponse>> recoveryEnd(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.recoveryEnd(IdentityHttp.sessionHash(request), id.toString(), key)));
    }

    @PostMapping("/api/exercises/{id}/preparation-retry")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> retry(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.retry(IdentityHttp.sessionHash(request), id.toString(), key)));
    }

    @PostMapping("/api/exercises/{id}/abandon")
    public ResponseEntity<ApiResponse<WorkspaceOperationResponse>> abandon(@PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(exercises.abandon(IdentityHttp.sessionHash(request), id.toString(), key)));
    }

    @GetMapping("/api/exercises/current")
    public ApiResponse<ExerciseResponse> current(HttpServletRequest request) {
        return ApiResponse.success(exercises.current(IdentityHttp.sessionHash(request)));
    }

    @GetMapping("/api/exercises/{id}")
    public ApiResponse<ExerciseResponse> find(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(exercises.find(IdentityHttp.sessionHash(request), id.toString()));
    }

    @GetMapping("/api/exercises/{id}/files")
    public ApiResponse<WorkspaceFileResponse> read(@PathVariable UUID id, @RequestParam String path, HttpServletRequest request) {
        return ApiResponse.success(exercises.read(IdentityHttp.sessionHash(request), id.toString(), path));
    }

    @GetMapping("/api/operations/{id}")
    public ApiResponse<WorkspaceOperationResponse> operation(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(exercises.operation(IdentityHttp.sessionHash(request), id.toString()));
    }
}
