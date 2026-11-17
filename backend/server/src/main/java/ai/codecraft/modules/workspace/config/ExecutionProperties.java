package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Must match the single sandbox coordinator's deployment slot count. */
@ConfigurationProperties("workspace.execution")
public record ExecutionProperties(@DefaultValue("4") int concurrency) {
    public ExecutionProperties {
        if (concurrency < 1 || concurrency > 64) throw new IllegalArgumentException("Execution concurrency must be between 1 and 64");
    }
}
