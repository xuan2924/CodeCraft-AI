package ai.codecraft.modules.copilot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("copilot")
public record CopilotProperties(@DefaultValue("2") int workers, @DefaultValue("262144") int responseBytes,
    @DefaultValue("1048576") int contextBytes, @DefaultValue("16") int batchTools,
    @DefaultValue("3") int noProgressLimit, @DefaultValue("130s") Duration workerLease) {
    public CopilotProperties {
        if (workers < 1 || workers > 8 || responseBytes < 4096 || responseBytes > 262144
            || contextBytes < responseBytes || contextBytes > 8 * 1024 * 1024 || batchTools < 1 || batchTools > 32
            || noProgressLimit < 1 || noProgressLimit > 10 || workerLease == null
            || workerLease.compareTo(Duration.ofSeconds(125)) < 0 || workerLease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid Copilot limits");
        }
    }
}
