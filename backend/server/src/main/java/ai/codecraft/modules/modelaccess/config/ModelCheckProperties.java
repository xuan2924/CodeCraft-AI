package ai.codecraft.modules.modelaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.checks")
public record ModelCheckProperties(@DefaultValue("5m") Duration startWindow, @DefaultValue("3") int startsPerWindow,
    @DefaultValue("65536") int maxDecodedBytes) {
    // Deduplicate explicit paid probes within this window; not an admission deadline.
    public static final Duration PROBE_REUSE_WINDOW = Duration.ofMinutes(30);

    public ModelCheckProperties {
        if (startWindow == null || startWindow.compareTo(Duration.ofMinutes(5)) < 0 || startWindow.compareTo(Duration.ofDays(1)) > 0
            || startsPerWindow < 1 || startsPerWindow > 3 || maxDecodedBytes < 1 || maxDecodedBytes > 65536) throw new IllegalArgumentException("Invalid model check admission limits");
    }
}
