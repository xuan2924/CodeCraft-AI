package ai.codecraft.modules.workspace.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.retention")
public record RetentionProperties(@DefaultValue("20") int historyLimit, @DefaultValue("30") int days,
    @DefaultValue("7") int progressDays, @DefaultValue({"60", "300", "1800", "3600"}) List<Integer> retrySeconds,
    @DefaultValue("24") int interventionHours, @DefaultValue("60") int claimSeconds) {
    public RetentionProperties {
        if (historyLimit < 1 || days < 1 || progressDays < 7 || retrySeconds == null || retrySeconds.isEmpty()
            || retrySeconds.stream().anyMatch(seconds -> seconds == null || seconds < 1) || interventionHours < 1 || claimSeconds < 10) {
            throw new IllegalArgumentException("Invalid retention policy");
        }
        retrySeconds = List.copyOf(retrySeconds);
    }
    public Duration retryDelay(int failures) { return Duration.ofSeconds(retrySeconds.get(Math.min(failures, retrySeconds.size() - 1))); }
}
