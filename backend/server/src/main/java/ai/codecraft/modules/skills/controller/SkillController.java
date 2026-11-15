package ai.codecraft.modules.skills.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.dto.SkillResponse;
import ai.codecraft.modules.skills.service.SkillService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
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
public class SkillController {
    private final SkillService skills;
    public SkillController(SkillService skills) { this.skills = skills; }
    @GetMapping("/api/skills")
    public ApiResponse<List<SkillResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(skills.list(IdentityHttp.sessionHash(request)));
    }
    @GetMapping("/api/skills/{id}/package")
    public ApiResponse<SkillPackageRequest> contents(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(skills.contents(IdentityHttp.sessionHash(request), id.toString()));
    }
    @PostMapping("/api/skills")
    public ApiResponse<SkillResponse> create(@RequestHeader("Idempotency-Key") String key, @RequestBody SkillPackageRequest body, HttpServletRequest request) {
        return ApiResponse.success(skills.save(IdentityHttp.sessionHash(request), null, key, null, body));
    }
    @PutMapping("/api/skills/{id}")
    public ApiResponse<SkillResponse> update(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
        @RequestParam String expectedVersion, @RequestBody SkillPackageRequest body, HttpServletRequest request) {
        return ApiResponse.success(skills.save(IdentityHttp.sessionHash(request), id.toString(), key, expectedVersion, body));
    }
    @PutMapping("/api/skills/{id}/enabled")
    public ApiResponse<SkillResponse> enabled(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
        @RequestParam String expectedVersion, @RequestParam boolean enabled, HttpServletRequest request) {
        return ApiResponse.success(skills.change(IdentityHttp.sessionHash(request), id.toString(), key, expectedVersion, enabled, false));
    }
    @DeleteMapping("/api/skills/{id}")
    public ApiResponse<SkillResponse> delete(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
        @RequestParam String expectedVersion, HttpServletRequest request) {
        return ApiResponse.success(skills.change(IdentityHttp.sessionHash(request), id.toString(), key, expectedVersion, false, true));
    }
}
