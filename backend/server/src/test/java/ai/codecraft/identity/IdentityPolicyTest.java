package ai.codecraft.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentityPolicyTest {
    @Test
    void normalizesOnlyAsciiAccountCase() {
        assertThat(IdentityPolicy.username("Alice_007-X")).isEqualTo("alice_007-x");
        for (String value : new String[] {"ab", "a".repeat(33), " alice", "alice ", "用户123", "alice@example"}) {
            assertThatThrownBy(() -> IdentityPolicy.username(value)).isInstanceOf(IdentityFailure.class);
        }
    }

    @Test
    void countsUnicodeCodePointsWithoutTrimmingOrNormalization() {
        IdentityPolicy.password("😀".repeat(8));
        IdentityPolicy.password("😀".repeat(128));
        IdentityPolicy.password("        ");
        for (String value : new String[] {"😀".repeat(7), "x".repeat(129), "😀".repeat(129), "1234567\uD800", "1234567\uDC00"}) {
            assertThatThrownBy(() -> IdentityPolicy.password(value)).isInstanceOf(IdentityFailure.class);
        }
    }

    @Test
    void idleRenewalNeverExtendsAbsoluteLifetime() {
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(IdentityPolicy.expiresAt(issued, issued)).isEqualTo(issued.plusSeconds(7 * 86400));
        assertThat(IdentityPolicy.expiresAt(issued, issued.plusSeconds(29 * 86400)))
            .isEqualTo(issued.plusSeconds(30 * 86400));
    }

    @Test
    void usesArgon2idWithExplicitParametersAndDistinctSalts() {
        IdentitySettings settings = new IdentitySettings("http://localhost:5173", "01".repeat(32), "", "");
        IdentitySecrets secrets = new IdentitySecrets(settings);
        String hash = secrets.hash("😀".repeat(8));
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(secrets.hash("😀".repeat(8))).isNotEqualTo(hash);
        assertThat(secrets.matches("😀".repeat(8), hash)).isTrue();
        assertThat(secrets.matches("😀".repeat(7) + "x", hash)).isFalse();
        assertThat(secrets.matches("12345678", null)).isFalse();
        String token = secrets.token();
        assertThat(token).hasSize(43);
        assertThat(IdentitySecrets.digest(token)).hasSize(64).isNotEqualTo(token);
        assertThat(secrets.fingerprint("ab", "c")).isNotEqualTo(secrets.fingerprint("a", "bc"));
    }

    @Test
    void productionCookiesAndOriginFallbackAreStrict() {
        IdentitySettings settings = new IdentitySettings("https://example.test", "01".repeat(32), "", "");
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        IdentityHttp.cookie(response, settings, IdentityHttp.SESSION_COOKIE, "fixture", 60);
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
            .doesNotContain("Domain=");
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Referer", "https://example.test/account");
        assertThat(IdentityHttp.trustedSource(request, settings.publicOrigin())).isTrue();
        request.addHeader("Origin", "null");
        assertThat(IdentityHttp.trustedSource(request, settings.publicOrigin())).isFalse();
        request.removeHeader("Origin");
        request.removeHeader("Referer");
        request.addHeader("Referer", "https://example.test.evil/account");
        assertThat(IdentityHttp.trustedSource(request, settings.publicOrigin())).isFalse();
    }

    @Test
    void productionCannotSilentlyUseInsecureCookies() {
        assertThat(new IdentitySettings("https://example.test", "01".repeat(32), "", "").secureCookie()).isTrue();
        for (String origin : new String[] {"http://example.test", "http://localhost.evil", "https://host/path", "https://a@host"}) {
            assertThatThrownBy(() -> new IdentitySettings(origin, "01".repeat(32), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new IdentitySettings("http://localhost:5173", "", "", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
