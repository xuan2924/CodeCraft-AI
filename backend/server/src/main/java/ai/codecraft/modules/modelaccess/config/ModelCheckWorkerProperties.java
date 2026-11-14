package ai.codecraft.modules.modelaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.checks.worker")
public record ModelCheckWorkerProperties(@DefaultValue("2") int concurrency) {
    public ModelCheckWorkerProperties {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("Invalid model check worker concurrency");
    }
}
