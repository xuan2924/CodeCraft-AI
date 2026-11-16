package ai.codecraft.modules.platformagent.service;

import ai.codecraft.modules.platformagent.model.PlatformRole;
import dev.langchain4j.model.input.PromptTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PlatformPrompts {
    private final PromptTemplate authority = load("authority");
    private final PromptTemplate check = load("check");
    public String authority(PlatformRole role) { return authority.apply(Map.of("role", role.name())).text(); }
    public String check(PlatformRole role) { return check.apply(Map.of("role", role.name())).text(); }
    private PromptTemplate load(String name) {
        try { return PromptTemplate.from(new ClassPathResource("prompts/platform/" + name + ".txt").getContentAsString(StandardCharsets.UTF_8)); }
        catch (IOException failure) { throw new UncheckedIOException("Missing platform prompt", failure); }
    }
}
