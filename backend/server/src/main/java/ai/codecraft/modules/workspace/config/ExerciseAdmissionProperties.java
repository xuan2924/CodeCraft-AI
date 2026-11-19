package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace.admission")
public record ExerciseAdmissionProperties(@DefaultValue("1") int maxUnfinished) {
    public ExerciseAdmissionProperties {
        if (maxUnfinished < 1) throw new IllegalArgumentException("max-unfinished must be positive");
    }
}
