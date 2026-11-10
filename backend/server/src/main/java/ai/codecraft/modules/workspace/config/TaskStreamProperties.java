package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.stream")
public record TaskStreamProperties(@DefaultValue("5") int perUser, @DefaultValue("32") int connections,
    @DefaultValue("1000") int pollMillis, @DefaultValue("15000") int heartbeatMillis,
    @DefaultValue("5000") int stalledMillis) {
    public TaskStreamProperties {
        if (perUser < 1 || perUser > 5 || connections < perUser || connections > 128
            || pollMillis < 100 || pollMillis > 1000 || heartbeatMillis < 1000 || heartbeatMillis > 15000
            || stalledMillis < 1000 || stalledMillis > 5000) throw new IllegalArgumentException("Invalid task stream limits");
    }
}
