package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.finalization")
public record FinalizationProperties(@DefaultValue("3") int maxAttempts, @DefaultValue("600") int budgetSeconds,
    @DefaultValue("5") int firstRetrySeconds, @DefaultValue("30") int secondRetrySeconds) {
    public FinalizationProperties {
        if (maxAttempts < 1 || maxAttempts > 3 || budgetSeconds < 1 || budgetSeconds > 600
            || firstRetrySeconds < 1 || secondRetrySeconds < firstRetrySeconds) {
            throw new IllegalArgumentException("Invalid final verification budget");
        }
    }
}
