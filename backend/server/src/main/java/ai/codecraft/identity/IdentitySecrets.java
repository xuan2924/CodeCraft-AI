package ai.codecraft.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class IdentitySecrets {
    private final SecureRandom random = new SecureRandom();
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final Semaphore hashSlots = new Semaphore(2);
    private final byte[] fingerprintKey;
    private final String dummyHash;

    public IdentitySecrets(IdentitySettings settings) {
        fingerprintKey = settings.fingerprintKey();
        dummyHash = encoder.encode(token());
    }

    public String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String password) {
        IdentityPolicy.password(password);
        if (!hashSlots.tryAcquire()) throw new IdentityFailure(429, "AUTH_BUSY");
        try { return encoder.encode(password); }
        finally { hashSlots.release(); }
    }

    public boolean matches(String password, String hash) {
        IdentityPolicy.password(password);
        if (!hashSlots.tryAcquire()) throw new IdentityFailure(429, "AUTH_BUSY");
        try { return encoder.matches(password, hash == null ? dummyHash : hash); }
        finally { hashSlots.release(); }
    }

    public String fingerprint(String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    public static String digest(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
