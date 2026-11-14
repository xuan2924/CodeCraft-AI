package ai.codecraft.modules.identity.security;

import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

public final class IdentityHttp {
    public static final String SESSION_COOKIE = "codecraft_session";
    public static final String ANONYMOUS_COOKIE = "codecraft_csrf";
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";
    public static final String EXPECTED_OWNER_HEADER = "X-Codecraft-Owner";

    private IdentityHttp() { }

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                if (value != null) return null;
                value = cookie.getValue();
            }
        }
        return value;
    }

    public static String sessionHash(HttpServletRequest request) {
        return IdentitySecrets.digest(cookie(request, SESSION_COOKIE));
    }

    public static String anonymousHash(HttpServletRequest request) {
        return IdentitySecrets.digest(cookie(request, ANONYMOUS_COOKIE));
    }

    public static void cookie(HttpServletResponse response, IdentitySettings settings, String name, String value, long seconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value).httpOnly(true).secure(settings.secureCookie())
            .sameSite("Lax").path("/").maxAge(Math.max(0, seconds)).build().toString());
    }

    public static void sessionCookie(HttpServletResponse response, IdentitySettings settings, String value, Instant now, Instant expiry) {
        cookie(response, settings, SESSION_COOKIE, value, Duration.between(now, expiry).toSeconds());
    }

    public static boolean trustedSource(HttpServletRequest request, String publicOrigin) {
        String origin = request.getHeader("Origin");
        if (origin != null) return publicOrigin.equals(origin);
        String referer = request.getHeader("Referer");
        if (referer == null) return false;
        try {
            URI uri = URI.create(referer);
            if (uri.getUserInfo() != null || uri.getHost() == null) return false;
            return publicOrigin.equals(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException exception) { return false; }
    }
}
