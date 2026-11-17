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

    private StreamingChatModel anthropic(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, String baseUrl, JdkHttpClientBuilder http) {
        var model = target.model();
        boolean thinking = model.thinkingMode() == ModelThinkingMode.ON;
        var builder = AnthropicStreamingChatModel.builder().baseUrl(baseUrl).apiKey(target.credential()).modelName(model.modelId())
            .maxTokens(limits.outputTokens()).temperature(model.temperature()).httpClientBuilder(http).returnThinking(true).sendThinking(thinking)
            .cacheSystemMessages(true).cacheTools(true).cacheAutomatically(true)
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
        builder.maxTokens(limits.outputTokens());
        if (preset == ModelProviderPreset.DEEPSEEK) {
            builder.customParameters(Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled")));
        }
        if (thinking && model.reasoningEffort() != null) builder.reasoningEffort(model.reasoningEffort());
        return builder.build();
    }
}
