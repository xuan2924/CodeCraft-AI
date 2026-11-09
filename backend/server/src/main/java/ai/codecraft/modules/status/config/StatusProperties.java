package ai.codecraft.modules.status.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tracer")
public record StatusProperties(int sandboxPort, @DefaultValue("127.0.0.1") String sandboxHost) {
    public StatusProperties {
        if (sandboxHost == null || !sandboxHost.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid sandbox host");
        if (sandboxPort < 1 || sandboxPort > 65535) {
            throw new IllegalArgumentException("SANDBOX_PORT must be between 1 and 65535");
        }
    }
}
