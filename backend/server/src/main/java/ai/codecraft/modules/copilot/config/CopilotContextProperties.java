package ai.codecraft.modules.copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("copilot.context")
public record CopilotContextProperties(@DefaultValue("80") int triggerPercent, @DefaultValue("4") int recentUnits,
    @DefaultValue("8192") int rereadBytes, @DefaultValue("4096") int minimumPrefixBytes,
    @DefaultValue("8192") int summaryBytes, @DefaultValue("512") int sourceMessages, @DefaultValue("2048") int summaryOutputTokens) {
    public CopilotContextProperties {
        if (triggerPercent < 50 || triggerPercent > 95 || recentUnits < 1 || recentUnits > 20
            || rereadBytes < 1024 || rereadBytes > 32768 || minimumPrefixBytes < 1024 || minimumPrefixBytes > 65536
            || summaryBytes < 1024 || summaryBytes > 32768 || sourceMessages < 32 || sourceMessages > 2048
            || summaryOutputTokens < 256 || summaryOutputTokens > 4096) throw new IllegalArgumentException("Invalid context compaction limits");
    }
}
