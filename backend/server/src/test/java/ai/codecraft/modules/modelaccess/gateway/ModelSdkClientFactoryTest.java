package ai.codecraft.modules.modelaccess.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import java.time.Duration;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SDK publishing boundary only: no subscription, network or provider compatibility claim. */
class ModelSdkClientFactoryTest {
    private final ModelSdkClientFactory factory = new ModelSdkClientFactory(new ModelConnectionValidator(), new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144));

    @Test
    void deepSeekProbeExplicitlyDisablesThinkingAndHonorsTheLowerOutputCap() {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, ModelThinkingMode.OFF), new ModelInvocationLimitsDTO(128, null), transport);
            var parameters = assertInstanceOf(OpenAiChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals("fixture-model", parameters.modelName());
            assertEquals(128, parameters.maxOutputTokens());
            assertEquals(Map.of("type", "disabled"), parameters.customParameters().get("thinking"));
        }
    }

    @Test
    void responsesUsesNativeStatelessReasoningParametersWithoutServerHistory() {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.OPENAI_RESPONSES, ModelProviderPreset.OPENAI, ModelThinkingMode.ON), new ModelInvocationLimitsDTO(2048, null), transport);
            var parameters = assertInstanceOf(OpenAiResponsesChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals(2048, parameters.maxOutputTokens());
            assertEquals(false, parameters.store());
            assertEquals("medium", parameters.reasoningEffort());
            assertEquals(List.of("reasoning.encrypted_content"), parameters.include());
        }
    }

    @Test
    void anthropicPreservesNativeThinkingWithAnExplicitProbeBudget() {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.ANTHROPIC, ModelProviderPreset.CUSTOM, ModelThinkingMode.ON), new ModelInvocationLimitsDTO(2048, 1024), transport);
            var parameters = assertInstanceOf(AnthropicChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals(2048, parameters.maxOutputTokens());
            assertEquals("enabled", parameters.thinkingType());
            assertEquals(1024, parameters.thinkingBudgetTokens());
            assertEquals(true, parameters.sendThinking());
            assertEquals(true, parameters.returnThinking());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ModelProviderPreset.class, names = {"GLM", "KIMI"})
    void namedCompatibleProvidersDoNotSilentlyUseTheirDefaultThinkingMode(ModelProviderPreset preset) {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.OPENAI_CHAT, preset, ModelThinkingMode.OFF), new ModelInvocationLimitsDTO(128, null), transport);
            var parameters = assertInstanceOf(OpenAiChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals(Map.of("type", "disabled"), parameters.customParameters().get("thinking"));
        }
    }

    @Test
    void knownReasoningReplayGapFailsExplicitlyWithoutProtocolFallback() {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var failure = assertThrows(BusinessException.class, () -> factory.create(target(ModelProtocol.OPENAI_RESPONSES, ModelProviderPreset.DEEPSEEK, ModelThinkingMode.ON), new ModelInvocationLimitsDTO(2048, null), transport));
            assertEquals("MODEL_PROTOCOL_UNSUPPORTED", failure.code());
        }
    }

    @Test
    void productionAddressValidationAndOutputCapsCannotBeBypassedAtDispatch() {
        var original = target(ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, ModelThinkingMode.OFF);
        var connection = original.connection();
        var insecure = new ModelConnectionResponse(connection.id(), connection.name(), "http://localhost", connection.protocol(), connection.preset(), "1", "1", true, false);
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            assertThrows(BusinessException.class, () -> factory.create(new ModelInvocationDTO(insecure, original.model(), original.credential()), new ModelInvocationLimitsDTO(128, null), transport));
            assertThrows(BusinessException.class, () -> factory.create(original, new ModelInvocationLimitsDTO(4097, null), transport));
            assertFalse(original.toString().contains(original.credential()));
        }
    }

    @ParameterizedTest
    @EnumSource(ModelThinkingMode.class)
    void openAiChatUsesTheNativeCompletionLimitAndExplicitReasoningMode(ModelThinkingMode mode) {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.OPENAI_CHAT, ModelProviderPreset.OPENAI, mode), new ModelInvocationLimitsDTO(2048, null), transport);
            var parameters = assertInstanceOf(OpenAiChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals(2048, parameters.maxCompletionTokens());
            assertEquals(mode == ModelThinkingMode.ON ? "medium" : "none", parameters.reasoningEffort());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ModelProviderPreset.class, names = {"OPENAI", "DEEPSEEK"})
    void responsesOffCannotSilentlyInheritProviderDefaultThinking(ModelProviderPreset preset) {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var client = factory.create(target(ModelProtocol.OPENAI_RESPONSES, preset, ModelThinkingMode.OFF), new ModelInvocationLimitsDTO(128, null), transport);
            var parameters = assertInstanceOf(OpenAiResponsesChatRequestParameters.class, client.defaultRequestParameters());
            assertEquals("none", parameters.reasoningEffort());
        }
    }

    @Test
    void knownForcedThinkingModelCannotBeDispatchedAsNonThinking() {
        try (var transport = new BoundedModelHttpClientBuilder(8192, 1024)) {
            var failure = assertThrows(BusinessException.class, () -> factory.create(target(ModelProtocol.OPENAI_CHAT, ModelProviderPreset.GLM, ModelThinkingMode.OFF, "glm-5.3"), new ModelInvocationLimitsDTO(128, null), transport));
            assertEquals("MODEL_PROTOCOL_UNSUPPORTED", failure.code());
        }
    }

    private ModelInvocationDTO target(ModelProtocol protocol, ModelProviderPreset preset, ModelThinkingMode thinking) {
        return target(protocol, preset, thinking, "fixture-model");
    }
    private ModelInvocationDTO target(ModelProtocol protocol, ModelProviderPreset preset, ModelThinkingMode thinking, String modelId) {
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://example.test/v1", protocol, preset, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", modelId, 100000, 4096, thinking, null, null, null, "1", "1", "USER_PROVIDED", false);
        return new ModelInvocationDTO(connection, model, "fixture-not-a-real-key");
    }
}
