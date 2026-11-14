package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.workspace.model.dto.ExerciseModelResponse;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExerciseModelController {
    private final ExerciseModelService models;
    public ExerciseModelController(ExerciseModelService models) { this.models = models; }

    @GetMapping("/api/exercises/{id}/model")
    public ApiResponse<ExerciseModelResponse> current(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(models.current(IdentityHttp.sessionHash(request), id.toString()));
    }

    @PostMapping("/api/exercises/{id}/model")
    public ApiResponse<ExerciseModelResponse> select(@PathVariable UUID id, @RequestBody ModelInvocationReferenceDTO input,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(models.select(IdentityHttp.sessionHash(request), id.toString(), credential, key, input));
    }
}
