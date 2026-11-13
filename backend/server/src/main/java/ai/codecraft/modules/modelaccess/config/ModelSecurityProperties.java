package ai.codecraft.modules.modelaccess.config;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.security")
public record ModelSecurityProperties(@DefaultValue("") String activeKeyId, @DefaultValue("") String keys) {
    public ModelSecurityProperties {
        if (activeKeyId == null || keys == null) throw new IllegalArgumentException("Invalid model keyring configuration");
        activeKeyId = activeKeyId.strip();
        keys = keys.strip();
        if (!activeKeyId.isEmpty() || !keys.isEmpty()) {
            if (!activeKeyId.matches("[a-zA-Z0-9_-]{1,64}") || !parse(keys).containsKey(activeKeyId)) {
                throw new IllegalArgumentException("Active model encryption key is unavailable");
            }
        }
    }

    public Map<String, SecretKey> keyring() { return parse(keys); }

    private static Map<String, SecretKey> parse(String encoded) {
        if (encoded.isEmpty()) return Map.of();
        Map<String, SecretKey> result = new LinkedHashMap<>();
        for (String entry : encoded.split(",", -1)) {
            String[] pair = entry.split(":", -1);
            if (pair.length != 2 || !pair[0].matches("[a-zA-Z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid model encryption key entry");
            }
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(pair[1]); }
            catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("Invalid model encryption key encoding", malformed); }
            if (bytes.length != 32 || result.putIfAbsent(pair[0], new SecretKeySpec(bytes, "AES")) != null) {
                throw new IllegalArgumentException("Invalid or duplicate model encryption key");
            }
        }
        return Map.copyOf(result);
    }

    @Override public String toString() { return "ModelSecurityProperties[activeKeyId=" + activeKeyId + ", keys=REDACTED]"; }
}
