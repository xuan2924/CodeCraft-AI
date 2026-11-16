package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.FavoriteRequest;
import ai.codecraft.modules.workspace.model.dto.FavoriteResponse;
import ai.codecraft.modules.workspace.service.FavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FavoriteController {
    private final FavoriteService service;
    public FavoriteController(FavoriteService service) { this.service = service; }

    @GetMapping("/api/problem-favorites")
    public ApiResponse<List<FavoriteResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(IdentityHttp.sessionHash(request)));
    }

    @PostMapping("/api/problem-favorites/{problem}")
    public ApiResponse<Void> set(@PathVariable String problem, @RequestBody FavoriteRequest input, HttpServletRequest request) {
        service.set(IdentityHttp.sessionHash(request), problem, input.favorite());
        return ApiResponse.success(null);
    }
}
