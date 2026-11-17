package ai.codecraft.modules.modelaccess.security;

import ai.codecraft.modules.workspace.service.TerminalModelAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Process-local signing key: restart revokes all grants. No provider key or session hash in claims. */
@Component
public class ProxyTokens {
    private final byte[] key = new byte[32];
    private final Clock clock;
    private final TerminalModelAccessService authority;
    public ProxyTokens(Clock clock, TerminalModelAccessService authority) {
        this.clock = clock; this.authority = authority; new SecureRandom().nextBytes(key);
    }
    public String issue(String task, String exercise, long epoch) {
        var grant = authority.authorize(task, exercise, epoch);
        String payload = grant.task() + ":" + grant.exercise() + ":" + grant.user() + ":" + epoch + ":" + clock.instant().plusSeconds(900).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
    }
    public TerminalModelAccessService.Authority verify(String token) {
        try {
            if (token == null || token.length() > 1024) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            String[] claims = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":", -1);
            if (claims.length != 5 || clock.instant().getEpochSecond() >= Long.parseLong(claims[4])) throw new IllegalArgumentException();
            for (int i = 0; i < 3; i++) UUID.fromString(claims[i]);
            var grant = authority.authorize(claims[0], claims[1], Long.parseLong(claims[3]));
            if (!grant.user().equals(claims[2])) throw new IllegalArgumentException();
            return grant;
        } catch (RuntimeException failure) { throw new IllegalStateException("Proxy forbidden"); }
    }
    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Proxy signing unavailable"); }
    }
}
