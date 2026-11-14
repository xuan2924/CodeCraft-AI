package ai.codecraft.modules.modelaccess.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckStartRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckStatusResponse;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelCheckController {
    private final ModelCheckService checks;
    public ModelCheckController(ModelCheckService checks) { this.checks = checks; }

    @PostMapping("/api/model-configurations/{model}/checks")
    public ResponseEntity<ApiResponse<ModelCheckResponse>> start(@PathVariable UUID model, @RequestBody ModelCheckStartRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var result = checks.start(IdentityHttp.sessionHash(request), model.toString(), key, input.feeAcknowledged());
        return ResponseEntity.accepted().location(URI.create("/api/model-checks/" + result.id())).body(ApiResponse.success(result));
    }

    @GetMapping("/api/model-checks/{id}")
    public ApiResponse<ModelCheckStatusResponse> status(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(checks.status(IdentityHttp.sessionHash(request), id.toString()));
    }

    @GetMapping("/api/model-configurations/{model}/checks/latest")
    public ApiResponse<ModelCheckStatusResponse> latest(@PathVariable UUID model, HttpServletRequest request) {
        return ApiResponse.success(checks.latest(IdentityHttp.sessionHash(request), model.toString()));
    }
}
