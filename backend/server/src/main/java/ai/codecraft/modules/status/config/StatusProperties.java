package ai.codecraft.modules.status.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracer")
public record StatusProperties(int sandboxPort) {
    public StatusProperties {
        if (sandboxPort < 1 || sandboxPort > 65535) {
            throw new IllegalArgumentException("SANDBOX_PORT must be between 1 and 65535");
        }
    }
}
