package ai.codecraft.modules.platformagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.agents")
public record PlatformAgentProperties(@DefaultValue("2") int workers, @DefaultValue("120") int requestSeconds,
    @DefaultValue("300") int queueSeconds, @DefaultValue("3") int checksPerWindow,
    @DefaultValue("300") int checkWindowSeconds, @DefaultValue("1800") int checkValiditySeconds, @DefaultValue("30") int checkRetentionDays) {
    public PlatformAgentProperties {
        if (workers < 1 || workers > 8 || requestSeconds < 1 || requestSeconds > 120 || queueSeconds < requestSeconds || queueSeconds > 600
            || checksPerWindow < 1 || checksPerWindow > 10 || checkWindowSeconds < 60 || checkWindowSeconds > 3600
            || checkValiditySeconds < 60 || checkValiditySeconds > 86400 || checkRetentionDays < 1 || checkRetentionDays > 365) throw new IllegalArgumentException("Invalid platform agent limits");
    }
}
