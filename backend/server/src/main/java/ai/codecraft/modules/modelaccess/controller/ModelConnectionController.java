package ai.codecraft.modules.modelaccess.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelDeleteRequest;
import ai.codecraft.modules.modelaccess.service.ModelConnectionService;
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
public class ModelConnectionController {
    private final ModelConnectionService connections;
    public ModelConnectionController(ModelConnectionService connections) { this.connections = connections; }

    @GetMapping("/api/model-connections")
    public ApiResponse<ModelConnectionsResponse> list(@RequestParam(value = "after", required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(connections.connections(IdentityHttp.sessionHash(request), after));
    }

    @GetMapping("/api/model-connections/{id}")
    public ApiResponse<ModelConnectionResponse> connection(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(connections.connection(IdentityHttp.sessionHash(request), id.toString()));
    }

    @PostMapping("/api/model-connections")
    public ResponseEntity<ApiResponse<ModelConnectionResponse>> create(@RequestBody ModelConnectionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var result = connections.create(IdentityHttp.sessionHash(request), key, input);
        return ResponseEntity.created(URI.create("/api/model-connections/" + result.id())).header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }

    @PutMapping("/api/model-connections/{id}")
    public ApiResponse<ModelConnectionResponse> update(@PathVariable UUID id, @RequestBody ModelConnectionRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(connections.update(IdentityHttp.sessionHash(request), id.toString(), key, input));
    }

    @DeleteMapping("/api/model-connections/{id}")
    public ApiResponse<ModelConnectionResponse> delete(@PathVariable UUID id, @RequestBody ModelDeleteRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(connections.delete(IdentityHttp.sessionHash(request), id.toString(), key, input.expectedVersion()));
    }
}
