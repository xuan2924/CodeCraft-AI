package ai.codecraft.modules.modelaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.calls")
public record ModelCallProperties(@DefaultValue("120s") Duration timeout, @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("8") int maxConcurrent, @DefaultValue("8388608") long responseBytes, @DefaultValue("65536") long errorBytes,
    @DefaultValue("262144") int maxDecodedBytes) {
    public ModelCallProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0
            || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(timeout) > 0
            || maxConcurrent < 1 || maxConcurrent > 8 || responseBytes < 1 || responseBytes > 8388608 || errorBytes < 1 || errorBytes > 65536
            || maxDecodedBytes < 1 || maxDecodedBytes > 262144) throw new IllegalArgumentException("Invalid model call limits");
    }
}
