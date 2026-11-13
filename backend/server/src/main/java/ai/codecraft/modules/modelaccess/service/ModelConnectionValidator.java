package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class ModelConnectionValidator {
    public URI baseUri(String value) {
        if (value == null || value.length() > 2048 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
        final URI parsed;
        try { parsed = URI.create(value); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.isOpaque()
            || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null || parsed.getPort() == 0 || parsed.getPort() > 65535) {
            throw invalid();
        }
        return parsed;
    }

    public void connection(ModelConnectionRequest input, boolean requireKey) {
        if (input == null || input.protocol() == null || input.preset() == null) throw invalid();
        name(input.name());
        baseUri(input.baseUrl());
        if (requireKey || input.apiKey() != null) {
            if (input.apiKey() == null || input.apiKey().isEmpty() || input.apiKey().length() > 4096
                || input.apiKey().chars().anyMatch(value -> value < 33 || value > 126)) throw invalid();
        }
    }

    public String name(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 128 || value.indexOf('\0') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
        return value.strip();
    }

    public void configuration(ModelConfigurationRequest input, ModelProtocol protocol) {
        if (input == null || input.thinkingMode() == null || input.modelId() == null || input.modelId().isEmpty() || input.modelId().length() > 256
            || !StandardCharsets.UTF_8.newEncoder().canEncode(input.modelId()) || input.modelId().codePoints().anyMatch(value -> Character.isWhitespace(value) || Character.isISOControl(value))) throw invalid();
        name(input.name());
        if (input.contextWindow() == null || input.contextWindow() < 1 || input.maxOutputTokens() == null || input.maxOutputTokens() < 1
            || input.maxOutputTokens() > 16384 || input.maxOutputTokens() >= input.contextWindow()) throw invalid();
        if (input.temperature() != null && (!Double.isFinite(input.temperature()) || input.temperature() < 0 || input.temperature() > 2)) throw invalid();
        if (input.reasoningEffort() != null && (!input.reasoningEffort().matches("[a-z_]{1,32}") || input.thinkingMode() != ModelThinkingMode.ON)) throw invalid();
        if (input.thinkingBudgetTokens() != null && (protocol != ModelProtocol.ANTHROPIC || input.thinkingMode() != ModelThinkingMode.ON
            || input.thinkingBudgetTokens() < 1024 || input.thinkingBudgetTokens() >= input.maxOutputTokens())) throw invalid();
        if (protocol == ModelProtocol.ANTHROPIC && input.thinkingMode() == ModelThinkingMode.ON && input.maxOutputTokens() <= 1024) throw invalid();
    }

    public String cursor(String value) {
        if (value == null) return null;
        try {
            if (value.length() != 36) throw invalid();
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    public long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }

    private BusinessException invalid() { return new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST); }
}
