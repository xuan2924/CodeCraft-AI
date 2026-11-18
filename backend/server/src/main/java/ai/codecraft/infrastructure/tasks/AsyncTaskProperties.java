package ai.codecraft.infrastructure.tasks;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("codecraft.async")
public record AsyncTaskProperties(String backend) {
    public AsyncTaskProperties {
        backend = backend == null ? "local" : backend;
        if (!backend.equals("local") && !backend.equals("rabbit")) throw new IllegalArgumentException("Unknown async backend");
    }
}
