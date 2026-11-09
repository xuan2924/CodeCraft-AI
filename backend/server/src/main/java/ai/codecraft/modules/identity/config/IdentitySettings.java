package ai.codecraft.modules.identity.config;

import java.net.URI;
import java.util.HexFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("identity")
public final class IdentitySettings {
    private final String publicOrigin;
    private final boolean secureCookie;
    private final byte[] fingerprintKey;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public IdentitySettings(String publicOrigin, String fingerprintKey,
                            @DefaultValue("") String bootstrapUsername,
                            @DefaultValue("") String bootstrapPassword) {
        if (publicOrigin == null) throw new IllegalArgumentException("Public origin is required");
        URI origin;
        try { origin = URI.create(publicOrigin); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid public origin"); }
        boolean https = "https".equals(origin.getScheme());
        boolean localHttp = "http".equals(origin.getScheme()) && "localhost".equals(origin.getHost());
        if ((!https && !localHttp) || origin.getHost() == null || origin.getUserInfo() != null
            || origin.getRawQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("Public origin must be HTTPS or local HTTP localhost, without path");
        }
        if (fingerprintKey == null || !fingerprintKey.matches("[a-fA-F0-9]{64}")) {
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
