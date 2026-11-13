package ai.codecraft.modules.modelaccess.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelDeleteRequest;
import ai.codecraft.modules.modelaccess.service.ModelConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelConfigurationController {
    private final ModelConfigurationService models;
    public ModelConfigurationController(ModelConfigurationService models) { this.models = models; }

    @GetMapping("/api/model-connections/{connection}/models")
    public ApiResponse<ModelConfigurationsResponse> list(@PathVariable UUID connection,
        @RequestParam(value = "after", required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(models.configurations(IdentityHttp.sessionHash(request), connection.toString(), after));
    }

    @PostMapping("/api/model-connections/{connection}/models")
    public ResponseEntity<ApiResponse<ModelConfigurationResponse>> create(@PathVariable UUID connection, @RequestBody ModelConfigurationRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var result = models.create(IdentityHttp.sessionHash(request), connection.toString(), key, input);
        return ResponseEntity.created(URI.create("/api/model-configurations/" + result.id())).body(ApiResponse.success(result));
    }

    @GetMapping("/api/model-configurations/{id}")
    public ApiResponse<ModelConfigurationResponse> model(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(models.configuration(IdentityHttp.sessionHash(request), id.toString()));
    }

    @PutMapping("/api/model-configurations/{id}")
    public ApiResponse<ModelConfigurationResponse> update(@PathVariable UUID id, @RequestBody ModelConfigurationRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(models.update(IdentityHttp.sessionHash(request), id.toString(), key, input));
    }

    @DeleteMapping("/api/model-configurations/{id}")
    public ApiResponse<ModelConfigurationResponse> delete(@PathVariable UUID id, @RequestBody ModelDeleteRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(models.delete(IdentityHttp.sessionHash(request), id.toString(), key, input.expectedVersion()));
    }
}
