package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.model.output.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CopilotCallEvidence {
    private final ObjectMapper json = JsonMapper.builder().visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY).build();

    public String configuration(ModelInvocationDTO target) {
        return encode(Map.of("connection", target.connection(), "model", target.model()));
    }

    public String usage(TokenUsage usage) { return encode(usage); }

    private String encode(Object value) {
        if (value == null) return null;
        try {
            String payload = json.writeValueAsString(value);
            if (payload.getBytes(StandardCharsets.UTF_8).length > 32768) throw new IllegalStateException("Model metadata exceeds reserved storage");
            return payload;
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode model metadata", failure); }
    }
}
