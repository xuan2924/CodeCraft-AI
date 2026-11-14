package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelProbeProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckDispatchDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Exactly one basic request and a two-request, side-effect-free tool roundtrip. No implicit retries. */
@Service
public class ModelCheckRunner {
    private static final String TOOL_NAME = "connection_probe";
    private final ModelCheckExecutionStore store;
    private final ModelCallDriver driver;
    private final ModelCheckPrompts prompts;
    private final ModelProbeProperties properties;
    private final ModelCallProperties calls;
    private final ObjectReader arguments;
    private final ToolSpecification tool = ToolSpecification.builder().name(TOOL_NAME).description("Return a fresh receipt for this check nonce; no external side effects.")
        .parameters(JsonObjectSchema.builder().addStringProperty("nonce").required("nonce").additionalProperties(false).build()).build();

    public ModelCheckRunner(ModelCheckExecutionStore store, ModelCallDriver driver, ModelCheckPrompts prompts,
        ModelProbeProperties properties, ModelCallProperties calls, ObjectMapper json) {
        this.store = store; this.driver = driver; this.prompts = prompts; this.properties = properties; this.calls = calls;
        arguments = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public void run(String checkId) {
        String worker = UUID.randomUUID().toString();
        String basicMarker = "basic_" + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString();
        String receipt = null;
        List<ChatMessage> messages = new ArrayList<>();
        for (int ordinal = store.firstOrdinal(checkId); ordinal <= 3; ordinal++) {
            var dispatch = store.begin(checkId, ordinal, worker);
            if (dispatch == null) return;
            int decodedBytes = 0;
            try {
                if (ordinal == 1) messages.add(prompts.basic(basicMarker));
                if (ordinal == 2) { messages.clear(); messages.add(prompts.tool(nonce)); }
                var request = ChatRequest.builder().messages(List.copyOf(messages));
                if (ordinal > 1) request.toolSpecifications(tool);
                var result = driver.call(dispatch.invocation(), request.build(), limits(dispatch, ordinal),
                    Math.min(dispatch.remainingDecodedBytes(), calls.maxDecodedBytes()), dispatch.notAfter());
                decodedBytes = result.decodedBytes();
                var response = result.response().aiMessage();
                if (ordinal == 1) exactText(response, basicMarker);
                if (ordinal == 3) exactText(response, receipt);
                if (ordinal == 2) {
                    var requested = response.toolExecutionRequests();
                    if (requested == null || requested.size() != 1) throw invalid();
                    var invocation = requested.get(0);
                    validateTool(invocation, nonce);
                    receipt = "receipt_" + UUID.randomUUID();
                    messages.add(response);
                    messages.add(ToolExecutionResultMessage.from(invocation, receipt));
                }
                if (!store.complete(dispatch.requestId(), worker, decodedBytes, null)) return;
            } catch (BusinessException failure) {
                var code = failure.errorCode() instanceof ModelAccessErrorCode known ? known : ModelAccessErrorCode.MODEL_PROVIDER_UNAVAILABLE;
                store.complete(dispatch.requestId(), worker, decodedBytes, code); return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }

    private ModelInvocationLimitsDTO limits(ModelCheckDispatchDTO dispatch, int ordinal) {
        var model = dispatch.invocation().model();
        boolean thinking = model.thinkingMode() == ModelThinkingMode.ON;
        int cap = thinking ? properties.thinkingOutputTokens() : ordinal == 1 ? properties.basicOutputTokens() : properties.toolOutputTokens();
        Integer budget = thinking && dispatch.invocation().connection().protocol() == ModelProtocol.ANTHROPIC ? 1024 : null;
        return new ModelInvocationLimitsDTO(Math.min(model.maxOutputTokens(), cap), budget);
    }

    private void validateTool(ToolExecutionRequest invocation, String nonce) {
        if (!TOOL_NAME.equals(invocation.name()) || invocation.id() == null || invocation.id().isBlank()
            || invocation.id().getBytes(StandardCharsets.UTF_8).length > 256 || invocation.id().codePoints().anyMatch(Character::isISOControl)
            || invocation.arguments() == null || invocation.arguments().length() > 4096) throw invalid();
        try {
            var value = arguments.readTree(invocation.arguments());
            if (value == null || !value.isObject() || value.size() != 1 || !value.has("nonce")
                || !value.get("nonce").isTextual() || !nonce.equals(value.get("nonce").textValue())) throw invalid();
        } catch (IOException malformed) { throw invalid(); }
    }

    private void exactText(AiMessage message, String expected) {
        if (message.toolExecutionRequests() != null && !message.toolExecutionRequests().isEmpty()
            || message.text() == null || !message.text().equals(expected)) throw invalid();
    }

    private BusinessException invalid() { return new BusinessException(ModelAccessErrorCode.MODEL_INVALID_RESPONSE); }
}
