package ai.codecraft.modules.modelaccess.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.input.PromptTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Built-in prompts rendered by the existing SDK template engine; no user-supplied templates. */
@Component
public class ModelCheckPrompts {
    private final PromptTemplate basic = load("basic");
    private final PromptTemplate tool = load("tool");

    public UserMessage basic(String marker) { return basic.apply(Map.of("marker", marker)).toUserMessage(); }
    public UserMessage tool(String nonce) { return tool.apply(Map.of("nonce", nonce)).toUserMessage(); }

    private PromptTemplate load(String name) {
        try { return PromptTemplate.from(new ClassPathResource("prompts/model-check/" + name + ".txt").getContentAsString(StandardCharsets.UTF_8)); }
        catch (IOException missing) { throw new UncheckedIOException("Missing built-in model check prompt", missing); }
    }
}
