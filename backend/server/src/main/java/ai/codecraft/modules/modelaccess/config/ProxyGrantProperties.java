package ai.codecraft.modules.modelaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.proxy")
public record ProxyGrantProperties(@DefaultValue("60m") Duration tokenTtl) {
    public ProxyGrantProperties {
        if (tokenTtl.toSeconds() < 1) throw new IllegalArgumentException("token-ttl must be at least one second");
    }
}
