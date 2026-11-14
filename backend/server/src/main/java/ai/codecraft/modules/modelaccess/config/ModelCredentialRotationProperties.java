package ai.codecraft.modules.modelaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("model.credential-rotation")
public record ModelCredentialRotationProperties(@DefaultValue("false") boolean enabled) {}
