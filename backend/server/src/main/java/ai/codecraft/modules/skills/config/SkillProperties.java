package ai.codecraft.modules.skills.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("skills")
public record SkillProperties(@DefaultValue("50") int maxSkills, @DefaultValue("262144") int maxPackageBytes,
    @DefaultValue("65536") int maxMainBytes, @DefaultValue("5242880") long maxOriginalBytes,
    @DefaultValue("32") int maxFiles, @DefaultValue("256") int maxPathBytes, @DefaultValue("4") int maxPathDepth) {
    public SkillProperties {
        if (maxSkills < 1 || maxSkills > 1000 || maxPackageBytes < 1 || maxPackageBytes > 1048576
            || maxMainBytes < 1 || maxMainBytes > maxPackageBytes || maxOriginalBytes < 1 || maxOriginalBytes > 1073741824L
            || maxFiles < 1 || maxFiles > 256 || maxPathBytes < 8 || maxPathBytes > 1024 || maxPathDepth < 2 || maxPathDepth > 16) {
            throw new IllegalArgumentException("Invalid skill limits");
        }
    }
}
