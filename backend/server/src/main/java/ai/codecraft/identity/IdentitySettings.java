package ai.codecraft.identity;

import java.net.URI;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class IdentitySettings {
    private final String publicOrigin;
    private final boolean secureCookie;
    private final byte[] fingerprintKey;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public IdentitySettings(@Value("${identity.public-origin}") String publicOrigin,
                            @Value("${identity.fingerprint-key}") String fingerprintKey,
                            @Value("${identity.bootstrap-username:}") String bootstrapUsername,
                            @Value("${identity.bootstrap-password:}") String bootstrapPassword) {
        URI origin;
        try { origin = URI.create(publicOrigin); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid public origin"); }
        boolean https = "https".equals(origin.getScheme());
        boolean localHttp = "http".equals(origin.getScheme()) && "localhost".equals(origin.getHost());
        if ((!https && !localHttp) || origin.getHost() == null || origin.getUserInfo() != null
            || origin.getRawQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("Public origin must be HTTPS or local HTTP localhost, without path");
        }
        if (!fingerprintKey.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Set IDENTITY_FINGERPRINT_KEY to 32 random bytes encoded as hex");
        }
        this.publicOrigin = publicOrigin;
        this.secureCookie = https;
        this.fingerprintKey = HexFormat.of().parseHex(fingerprintKey);
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
    }

    public String publicOrigin() { return publicOrigin; }
    public boolean secureCookie() { return secureCookie; }
    public byte[] fingerprintKey() { return fingerprintKey.clone(); }
    public String bootstrapUsername() { return bootstrapUsername; }
    public String bootstrapPassword() { return bootstrapPassword; }
}
