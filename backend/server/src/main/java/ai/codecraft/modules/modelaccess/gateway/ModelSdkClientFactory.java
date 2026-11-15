package ai.codecraft.modules.modelaccess.gateway;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import org.slf4j.helpers.NOPLogger;
import org.springframework.stereotype.Component;

/** Protocol mapping only. The caller owns dispatch authorization, deadlines and transport lifetime. */
@Component
public class ModelSdkClientFactory {
    private final ModelConnectionValidator validator;
    private final ModelCallProperties properties;
    public ModelSdkClientFactory(ModelConnectionValidator validator, ModelCallProperties properties) { this.validator = validator; this.properties = properties; }

    public StreamingChatModel create(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, BoundedModelHttpClientBuilder transport) {
        if (target == null || target.connection() == null || target.model() == null || target.credential() == null || target.credential().isBlank()
            || target.credential().getBytes(StandardCharsets.UTF_8).length > 4096 || limits == null || transport == null
            || target.connection().deleted() || target.model().deleted() || !target.connection().id().equals(target.model().connectionId())
            || limits.outputTokens() < 1 || limits.outputTokens() > target.model().maxOutputTokens() || limits.outputTokens() > 16384) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        }
        var selected = target.model();
        validator.configuration(new ModelConfigurationRequest(selected.name(), selected.modelId(), selected.contextWindow(), selected.maxOutputTokens(),
            selected.thinkingMode(), selected.reasoningEffort(), selected.thinkingBudgetTokens(), selected.temperature(), null), target.connection().protocol());
        String modelId = selected.modelId().toLowerCase(Locale.ROOT);
        boolean requiresThinking = switch (target.connection().preset()) {
            case GLM -> Set.of("glm-5.3", "glm-5.3-flash").contains(modelId);
            case KIMI -> Set.of("kimi-k3", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k2.7-code", "kimi-k2.7-code-highspeed").contains(modelId);
            default -> false;
        };
        if (requiresThinking && selected.thinkingMode() == ModelThinkingMode.OFF
            || target.connection().preset() == ModelProviderPreset.OPENAI && unsupportedOpenAiHistory(target.connection().protocol(), modelId)) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_PROTOCOL_UNSUPPORTED);
        }
        if (limits.thinkingBudgetTokens() != null && (target.connection().protocol() != ModelProtocol.ANTHROPIC || selected.thinkingMode() != ModelThinkingMode.ON
            || limits.thinkingBudgetTokens() > (selected.thinkingBudgetTokens() == null ? 1024 : selected.thinkingBudgetTokens()))) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        }
        String baseUrl = validator.baseUri(target.connection().baseUrl()).toString();
        var http = new JdkHttpClientBuilder().httpClientBuilder(transport).connectTimeout(properties.connectTimeout())
            .readTimeout(properties.timeout()).streamingBackpressure(true);
        return switch (target.connection().protocol()) {
            case OPENAI_CHAT -> chat(target, limits, baseUrl, http);
            case OPENAI_RESPONSES -> responses(target, limits, baseUrl, http);
            case ANTHROPIC -> anthropic(target, limits, baseUrl, http);
        };
    }

    private boolean unsupportedOpenAiHistory(ModelProtocol protocol, String modelId) {
        // 1.23.0 drops Responses assistant phase. Do not silently flatten these documented phase-aware families.
        if (protocol == ModelProtocol.OPENAI_RESPONSES) return List.of("gpt-5.4", "gpt-5.5", "gpt-5.6", "gpt-6", "gpt-6.1").stream()
            .anyMatch(family -> modelId.equals(family) || modelId.startsWith(family + "-"));
        // These models explicitly require Responses for function calling.
        return protocol == ModelProtocol.OPENAI_CHAT && Set.of("gpt-6-astra", "gpt-6.1-sol").contains(modelId);
    }

    private StreamingChatModel anthropic(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, String baseUrl, JdkHttpClientBuilder http) {
        var model = target.model();
        boolean thinking = model.thinkingMode() == ModelThinkingMode.ON;
        var builder = AnthropicStreamingChatModel.builder().baseUrl(baseUrl).apiKey(target.credential()).modelName(model.modelId())
            .maxTokens(limits.outputTokens()).temperature(model.temperature()).httpClientBuilder(http).returnThinking(true).sendThinking(thinking)
            .logRequests(false).logResponses(false).logger(NOPLogger.NOP_LOGGER);
        if (thinking) {
            int budget = limits.thinkingBudgetTokens() != null ? limits.thinkingBudgetTokens() : model.thinkingBudgetTokens() == null ? 1024 : model.thinkingBudgetTokens();
            if (budget < 1024 || budget >= limits.outputTokens()) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
            builder.thinkingType("enabled").thinkingBudgetTokens(budget);
        } else builder.thinkingType("disabled");
        return builder.build();
    }

    private StreamingChatModel responses(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, String baseUrl, JdkHttpClientBuilder http) {
        var model = target.model();
        boolean thinking = model.thinkingMode() == ModelThinkingMode.ON;
        if (thinking && target.connection().preset() == ModelProviderPreset.DEEPSEEK) throw new BusinessException(ModelAccessErrorCode.MODEL_PROTOCOL_UNSUPPORTED);
        // This SDK appends "/responses" literally, unlike the other protocol clients.
        // Normalize only a trailing path separator; never rewrite a query string.
        String endpoint = URI.create(baseUrl).getRawQuery() == null ? baseUrl.replaceFirst("/+$", "") : baseUrl;
        var builder = OpenAiResponsesStreamingChatModel.builder().baseUrl(endpoint).apiKey(target.credential()).modelName(model.modelId())
            .maxOutputTokens(limits.outputTokens()).temperature(model.temperature()).httpClientBuilder(http).store(false)
            .logRequests(false).logResponses(false);
        if (thinking) builder.reasoningEffort(model.reasoningEffort() == null ? "medium" : model.reasoningEffort())
            .reasoningSummary("auto").include(List.of("reasoning.encrypted_content"));
        else builder.reasoningEffort("none");
        return builder.build();
    }

    private StreamingChatModel chat(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, String baseUrl, JdkHttpClientBuilder http) {
        var model = target.model();
        boolean thinking = model.thinkingMode() == ModelThinkingMode.ON;
        var builder = OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(target.credential()).modelName(model.modelId())
            .temperature(model.temperature()).httpClientBuilder(http).returnThinking(true).sendThinking(thinking)
            .logRequests(false).logResponses(false).logger(NOPLogger.NOP_LOGGER);
        var preset = target.connection().preset();
        if (preset == ModelProviderPreset.OPENAI) {
            builder.maxCompletionTokens(limits.outputTokens());
            builder.reasoningEffort(thinking ? model.reasoningEffort() == null ? "medium" : model.reasoningEffort() : "none");
        } else builder.maxTokens(limits.outputTokens());
        if (preset == ModelProviderPreset.DEEPSEEK || preset == ModelProviderPreset.GLM || preset == ModelProviderPreset.KIMI && !thinking) {
            builder.customParameters(preset == ModelProviderPreset.GLM && thinking
                ? Map.of("thinking", Map.of("type", "enabled"), "clear_thinking", false)
                : Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled")));
        }
        if (thinking && model.reasoningEffort() != null) builder.reasoningEffort(model.reasoningEffort());
        return builder.build();
    }
}
