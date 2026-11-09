package ai.codecraft.modules.identity.security;

import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.service.IdentityPolicy;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

final class DatabaseCsrfRepository implements CsrfTokenRepository {
    private final IdentityTransactionService store;
    private final IdentitySecrets secrets;
    private final IdentitySettings settings;

    DatabaseCsrfRepository(IdentityTransactionService store, IdentitySecrets secrets, IdentitySettings settings) {
        this.store = store;
        this.secrets = secrets;
        this.settings = settings;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) { return token(secrets.token()); }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            store.invalidateCsrf(IdentityHttp.sessionHash(request), IdentityHttp.anonymousHash(request), secrets.token());
            IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, "", 0);
            return;
        }
        String context = secrets.token();
        store.anonymous(IdentitySecrets.digest(context), token.getToken());
        IdentityHttp.cookie(response, settings, IdentityHttp.ANONYMOUS_COOKIE, context,
            IdentityPolicy.ANONYMOUS_LIMIT.toSeconds());
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String value = store.csrf(IdentityHttp.sessionHash(request), IdentityHttp.anonymousHash(request));
        return value == null ? null : token(value);
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(IdentityHttp.CSRF_HEADER, "_csrf", value);
    }
}
