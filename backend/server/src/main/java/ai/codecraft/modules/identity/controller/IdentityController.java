package ai.codecraft.modules.identity.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.model.dto.CreateAccountRequest;
import ai.codecraft.modules.identity.model.dto.CreatedAccountResponse;
import ai.codecraft.modules.identity.model.dto.CsrfResponse;
import ai.codecraft.modules.identity.model.dto.LoginRequest;
import ai.codecraft.modules.identity.model.dto.LoginResultDTO;
import ai.codecraft.modules.identity.model.dto.PasswordRequest;
import ai.codecraft.modules.identity.model.dto.PasswordResetResponse;
import ai.codecraft.modules.identity.model.dto.SessionResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.identity.service.IdentityService;
import ai.codecraft.modules.identity.service.IdentityTransactionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final IdentityTransactionService store;
    private final IdentityService service;
    private final IdentitySettings settings;
    private final Clock clock;

    public IdentityController(IdentityTransactionService store, IdentityService service, IdentitySettings settings, Clock clock) {
        this.store = store;
        this.service = service;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/api/auth/csrf")
    public ApiResponse<CsrfResponse> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ApiResponse.success(new CsrfResponse(token.getToken()));
    }

    @GetMapping("/api/auth/session")
    public ApiResponse<SessionResponse> session(HttpServletRequest request) {
        return ApiResponse.success(store.authenticate(IdentityHttp.sessionHash(request)).identity());
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<SessionResponse> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        LoginResultDTO login = service.login(input.username(), input.password(), request.getRemoteAddr(),
            IdentityHttp.anonymousHash(request), request.getHeader(IdentityHttp.CSRF_HEADER), IdentityHttp.sessionHash(request));
        IdentityHttp.sessionCookie(response, settings, login.token(), clock.instant(), login.identity().expiresAt());
        IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, "", 0);
        return ApiResponse.success(login.identity());
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        store.logout(IdentityHttp.sessionHash(request), IdentityHttp.anonymousHash(request));
        clearCookies(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/password")
    public ResponseEntity<Void> password(@RequestBody PasswordRequest input,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        HttpServletRequest request, HttpServletResponse response) {
        service.changePassword(IdentityHttp.sessionHash(request), input.currentPassword(), input.newPassword(), key,
            request.getRemoteAddr());
        clearCookies(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/users")
    public ResponseEntity<ApiResponse<CreatedAccountResponse>> create(@RequestBody CreateAccountRequest input,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest request, HttpServletResponse response) {
        CreatedAccountResponse created = service.createUser(IdentityHttp.sessionHash(request), input.username(), key);
        refreshCookie(request, response);
        return ResponseEntity.status(created.credentialDelivered() ? 201 : 200).body(ApiResponse.success(created));
    }

    @PostMapping("/api/admin/users/{id}/password-reset")
    public ApiResponse<PasswordResetResponse> reset(@PathVariable UUID id,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   HttpServletRequest request, HttpServletResponse response) {
        PasswordResetResponse result = service.resetPassword(IdentityHttp.sessionHash(request), id.toString(), key);
        if (result.currentSessionRevoked()) clearCookies(response);
        else refreshCookie(request, response);
        return ApiResponse.success(result);
    }

    private void refreshCookie(HttpServletRequest request, HttpServletResponse response) {
        SessionResponse identity = store.authenticate(IdentityHttp.sessionHash(request)).identity();
        IdentityHttp.sessionCookie(response, settings, IdentityHttp.cookie(request, IdentityHttp.SESSION_COOKIE),
            clock.instant(), identity.expiresAt());
    }

    private void clearCookies(HttpServletResponse response) {
        IdentityHttp.cookie(response, settings, IdentityHttp.SESSION_COOKIE, "", 0);
        IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, "", 0);
    }
}
