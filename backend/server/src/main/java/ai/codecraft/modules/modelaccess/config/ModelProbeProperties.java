package ai.codecraft.modules.modelaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.checks.probe")
public record ModelProbeProperties(@DefaultValue("128") int basicOutputTokens, @DefaultValue("256") int toolOutputTokens,
    @DefaultValue("2048") int thinkingOutputTokens) {
    public ModelProbeProperties {
        if (basicOutputTokens < 1 || basicOutputTokens > 128 || toolOutputTokens < 1 || toolOutputTokens > 256
            || thinkingOutputTokens < 1025 || thinkingOutputTokens > 2048) throw new IllegalArgumentException("Invalid model check output limits");
    }
}
