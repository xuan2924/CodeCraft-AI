package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDefinitionDTO;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.platformagent.model.exception.PlatformAgentErrorCode;
import ai.codecraft.modules.skills.service.SkillPackageParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PlatformDefinitions {
    public static final String POLICY_VERSION = "INTERVIEW_V1";
    private static final int MAX_CONTEXT_BYTES = 65536;
    private final ObjectMapper json;
    private final ModelConnectionValidator models;
    private final SkillPackageParser skills;
    private final PlatformPrompts prompts;

    public PlatformDefinitions(ObjectMapper json, ModelConnectionValidator models, SkillPackageParser skills, PlatformPrompts prompts) {
        this.json = json; this.models = models; this.skills = skills; this.prompts = prompts;
    }

    public PlatformDefinitionDTO prepare(String id, PlatformDraftRequest input) {
        if (input == null || input.role() == null || input.instructions() == null || input.instructions().isBlank()
            || input.instructions().indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(input.instructions())
            || input.skills() == null || input.skills().size() > 10) throw invalid();
        models.connection(input.connection(), true);
        models.configuration(input.model(), input.connection().protocol());
        var packages = input.skills().stream().map(skills::parse).toList();
        if (input.instructions().getBytes(StandardCharsets.UTF_8).length + packages.stream().mapToLong(value -> value.contentBytes()).sum() > MAX_CONTEXT_BYTES
            || packages.stream().map(value -> value.name()).distinct().count() != packages.size()) throw invalid();
        var connection = input.connection();
        var model = input.model();
        return new PlatformDefinitionDTO(new ModelConnectionResponse(id, models.name(connection.name()), connection.baseUrl(), connection.protocol(),
            connection.preset(), "1", "1", true, false), new ModelConfigurationResponse(id, id, models.name(model.name()), model.modelId(),
            model.contextWindow(), model.maxOutputTokens(), model.thinkingMode(), model.reasoningEffort(), model.thinkingBudgetTokens(), model.temperature(),
            "1", "1", "ADMIN_CONFIGURED", false), input.instructions(), packages, POLICY_VERSION);
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode platform configuration", failure); }
    }

    public PlatformDefinitionDTO decode(String value) {
        try { return json.readValue(value, PlatformDefinitionDTO.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid stored platform configuration", failure); }
    }

    public ModelUsageReceiptDTO usage(String value) {
        if (value == null) return null;
        try { return json.readValue(value, ModelUsageReceiptDTO.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid platform usage receipt", failure); }
    }

    public void validateCheck(PlatformRole role, String text) {
        try {
            var value = json.readTree(text == null ? "null" : text);
            if (value == null) throw checkInvalid();
            var fields = new HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
            if (!value.isObject() || !fields.equals(Set.of("role", "message", "evidenceIds")) || !role.name().equals(value.path("role").asText())
                || !value.path("message").isTextual() || value.path("message").asText().isBlank() || value.path("message").asText().length() > 8192
                || !value.path("evidenceIds").isArray() || value.path("evidenceIds").size() != 1
                || !"fixture".equals(value.path("evidenceIds").get(0).asText())) throw checkInvalid();
        } catch (JsonProcessingException failure) { throw checkInvalid(); }
    }

    public String instructions(PlatformRole role, PlatformDefinitionDTO definition) {
        var text = new StringBuilder(prompts.authority(role));
        text.append(definition.instructions());
        for (var skill : definition.skills()) {
            text.append("\nPlatform Skill: ").append(skill.name());
            for (var file : skill.files()) text.append("\nFile: ").append(file.path()).append('\n').append(file.content());
        }
        return text.toString();
    }

    private BusinessException invalid() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_INVALID_REQUEST); }
    private BusinessException checkInvalid() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_INVALID_RESPONSE); }
}
