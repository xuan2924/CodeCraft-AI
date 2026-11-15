package ai.codecraft.modules.copilot.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("copilot.web")
public record CopilotWebProperties(@DefaultValue("") String apiKey, @DefaultValue("https://api.tavily.com/") URI baseUrl,
    @DefaultValue("20s") Duration timeout) {
    public CopilotWebProperties {
        if (apiKey == null || apiKey.length() > 512 || apiKey.contains("\n") || apiKey.contains("\r") || baseUrl == null
            || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme())) || baseUrl.getHost() == null
            || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
            || !baseUrl.getPath().endsWith("/") || timeout == null || timeout.toMillis() < 50 || timeout.toSeconds() > 30) {
            throw new IllegalArgumentException("Invalid Copilot web service configuration");
        }
    }
    @Override public String toString() { return "CopilotWebProperties[REDACTED]"; }
}
