package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.StorageBudgetResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorageCapacityController {
    private final StorageCapacityService capacity;
    public StorageCapacityController(StorageCapacityService capacity) { this.capacity = capacity; }
    @GetMapping("/api/exercises/{id}/storage")
    public ApiResponse<StorageBudgetResponse> usage(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(capacity.usage(IdentityHttp.sessionHash(request), id.toString()));
    }
}
