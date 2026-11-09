package ai.codecraft.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    public record LoginRequest(String username, String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record PasswordRequest(String currentPassword, String newPassword) {
        @Override public String toString() { return "PasswordRequest[redacted]"; }
    }
    public record CreateRequest(String username) { }

    private final IdentityStore store;
    private final IdentityService service;
    private final IdentitySettings settings;
    private final Clock clock;

    public IdentityController(IdentityStore store, IdentityService service, IdentitySettings settings, Clock clock) {
        this.store = store;
        this.service = service;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/api/auth/csrf")
    public IdentityHttp.Envelope csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return IdentityHttp.success(Map.of("token", token.getToken()));
    }

    @GetMapping("/api/auth/session")
    public IdentityHttp.Envelope session(HttpServletRequest request) {
        return IdentityHttp.success(store.authenticate(IdentityHttp.sessionHash(request)).identity());
    }

    @PostMapping("/api/auth/login")
    public IdentityHttp.Envelope login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        IdentityService.Login login = service.login(input.username(), input.password(), request.getRemoteAddr(),
            IdentityHttp.anonymousHash(request), request.getHeader(IdentityHttp.CSRF_HEADER), IdentityHttp.sessionHash(request));
        IdentityHttp.sessionCookie(response, settings, login.token(), clock.instant(), login.identity().expiresAt());
        IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, "", 0);
        return IdentityHttp.success(login.identity());
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
    public ResponseEntity<IdentityHttp.Envelope> create(@RequestBody CreateRequest input,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest request, HttpServletResponse response) {
        IdentityStore.Created created = service.createUser(IdentityHttp.sessionHash(request), input.username(), key);
        IdentityStore.Identity identity = store.authenticate(IdentityHttp.sessionHash(request)).identity();
        IdentityHttp.sessionCookie(response, settings, IdentityHttp.cookie(request, IdentityHttp.SESSION_COOKIE),
            clock.instant(), identity.expiresAt());
        return ResponseEntity.status(created.credentialDelivered() ? 201 : 200).body(IdentityHttp.success(created));
    }

    private void clearCookies(HttpServletResponse response) {
        IdentityHttp.cookie(response, settings, IdentityHttp.SESSION_COOKIE, "", 0);
        IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, "", 0);
    }
}
