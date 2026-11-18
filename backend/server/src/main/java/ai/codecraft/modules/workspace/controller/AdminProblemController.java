package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.AdminProblemResponse;
import ai.codecraft.modules.workspace.service.AdminProblemStore;
import ai.codecraft.modules.workspace.service.ProblemService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/problems")
public class AdminProblemController {
    private final AdminProblemStore store;
    private final ProblemService problems;
    public AdminProblemController(AdminProblemStore store, ProblemService problems) {
        this.store = store;
        this.problems = problems;
    }
    @GetMapping
    public ApiResponse<List<AdminProblemResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(store.list(IdentityHttp.sessionHash(request)));
    }
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<AdminProblemResponse.Validation>> validate(@RequestBody byte[] artifact, HttpServletRequest request) {
        store.authorize(IdentityHttp.sessionHash(request), false);
        var result = problems.validate(artifact);
        return ResponseEntity.status(result.valid() ? 200 : 400).body(ApiResponse.success(result));
    }
    @PostMapping("/publish")
    public ApiResponse<AdminProblemResponse.Published> publish(@RequestBody byte[] artifact,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.success(problems.publish(IdentityHttp.sessionHash(request), key, artifact, store));
    }
    @PostMapping("/{id}/availability")
    public ApiResponse<Void> availability(@PathVariable UUID id, @RequestBody AdminProblemResponse.Availability change, HttpServletRequest request) {
        store.availability(IdentityHttp.sessionHash(request), id.toString(), change.available());
        return ApiResponse.success(null);
    }
}
