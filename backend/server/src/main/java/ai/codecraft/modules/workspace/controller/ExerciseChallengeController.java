package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.ExerciseChallengeResponse;
import ai.codecraft.modules.workspace.service.ExerciseChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExerciseChallengeController {
    private final ExerciseChallengeService challenges;
    public ExerciseChallengeController(ExerciseChallengeService challenges) { this.challenges = challenges; }
    @GetMapping("/api/exercises/{exercise}/challenge")
    public ApiResponse<ExerciseChallengeResponse> find(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(challenges.find(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @PostMapping("/api/exercises/{exercise}/challenge")
    public ApiResponse<ExerciseChallengeResponse> enter(@PathVariable UUID exercise,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Operation-Credential", required = false) String credential, HttpServletRequest request) {
        return ApiResponse.success(challenges.enter(IdentityHttp.sessionHash(request), exercise.toString(), key, credential));
    }
}
