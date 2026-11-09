package ai.codecraft.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseCookie;

final class IdentityHttp {
    static final String SESSION_COOKIE = "codecraft_session";
    static final String ANONYMOUS_COOKIE = "codecraft_csrf";
    static final String CSRF_HEADER = "X-CSRF-TOKEN";
    record Envelope(String code, String message, Object data, String requestId) { }

    private IdentityHttp() { }

    static Envelope success(Object data) { return new Envelope("OK", "操作成功", data, UUID.randomUUID().toString()); }

    static void error(HttpServletResponse response, ObjectMapper json, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), new Envelope(code, "请求未完成，请检查输入或重新登录", null,
            UUID.randomUUID().toString()));
    }

    static String cookie(HttpServletRequest request, String name) {
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

    static String sessionHash(HttpServletRequest request) {
        return IdentitySecrets.digest(cookie(request, SESSION_COOKIE));
    }

    static String anonymousHash(HttpServletRequest request) {
        return IdentitySecrets.digest(cookie(request, ANONYMOUS_COOKIE));
    }

    static void cookie(HttpServletResponse response, IdentitySettings settings, String name, String value, long seconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value).httpOnly(true).secure(settings.secureCookie())
            .sameSite("Lax").path("/").maxAge(Math.max(0, seconds)).build().toString());
    }

    static void sessionCookie(HttpServletResponse response, IdentitySettings settings, String value, Instant now, Instant expiry) {
        cookie(response, settings, SESSION_COOKIE, value, Duration.between(now, expiry).toSeconds());
    }

    static boolean trustedSource(HttpServletRequest request, String publicOrigin) {
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
