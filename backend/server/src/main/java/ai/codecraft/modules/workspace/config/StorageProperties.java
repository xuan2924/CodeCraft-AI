package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.storage")
public record StorageProperties(@DefaultValue("536870912") long maxBytes,
    @DefaultValue("268435456") long recoveryBytes, @DefaultValue("20") int completedTests) {
    public StorageProperties {
        if (maxBytes <= 0 || recoveryBytes < 256L * 1024 * 1024 || recoveryBytes >= maxBytes || completedTests < 1) {
            throw new IllegalArgumentException("Invalid workspace storage budget");
        }
    }
}
