package ai.codecraft.modules.platformagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.service.SkillPackageParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformDefinitionsTest {
    private final PlatformDefinitions definitions = new PlatformDefinitions(new ObjectMapper(), new ModelConnectionValidator(),
        new SkillPackageParser(new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4)), new PlatformPrompts());

    @Test
    void snapshotsContainOnlyNonSecretConfigurationAndRoundTrip() {
        var definition = definitions.prepare("version", input("Explain only the published requirements."));
        String encoded = definitions.encode(definition);
        assertThat(encoded).doesNotContain("secret-platform-key", "apiKey", "expectedVersion");
        assertThat(definitions.decode(encoded)).isEqualTo(definition);
        assertThat(input("instructions").toString()).doesNotContain("secret-platform-key");
    }

    @Test
    void rejectsMissingOrUnboundedInstructionsAndFabricatedCheckEvidence() {
        assertThatThrownBy(() -> definitions.prepare("version", input(""))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> definitions.prepare("version", input("a".repeat(65537)))).isInstanceOf(BusinessException.class);
        definitions.validateCheck(PlatformRole.INTERVIEWER, "{\"role\":\"INTERVIEWER\",\"message\":\"Requirements only\",\"evidenceIds\":[\"fixture\"]}");
        assertThatThrownBy(() -> definitions.validateCheck(PlatformRole.INTERVIEWER,
            "{\"role\":\"INTERVIEWER\",\"message\":\"Pass\",\"evidenceIds\":[\"invented\"]}")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> definitions.validateCheck(PlatformRole.EVALUATOR,
            "{\"role\":\"EVALUATOR\",\"message\":\"Pass\",\"evidenceIds\":[\"fixture\"],\"stage\":\"PASSED\"}")).isInstanceOf(BusinessException.class);
    }

    private PlatformDraftRequest input(String instructions) {
        return new PlatformDraftRequest(PlatformRole.INTERVIEWER,
            new ModelConnectionRequest("Platform", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "secret-platform-key", null),
            new ModelConfigurationRequest("Platform", "deepseek-chat", 32768, 4096, ModelThinkingMode.OFF, null, null, null, null),
            instructions, List.of());
    }
}
