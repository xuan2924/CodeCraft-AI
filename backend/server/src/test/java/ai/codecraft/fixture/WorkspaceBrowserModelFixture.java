package ai.codecraft.fixture;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.gateway.ModelSdkClientFactory;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Disposable workspace-browser prerequisite only. Never packaged in the application image.
 * Only built-in checks of the exact synthetic connection are accepted; no network fallback exists. */
@Component
@Primary
@ConditionalOnProperty(name = "WORKSPACE_BROWSER_E2E", havingValue = "disposable")
public class WorkspaceBrowserModelFixture extends ModelCallDriver {
    public WorkspaceBrowserModelFixture(ModelSdkClientFactory factory, ModelCallProperties properties, Clock clock) {
        super(factory, properties, clock);
    }

    @Override
    public ModelCallResultDTO call(ModelInvocationDTO target, ChatRequest request, ModelInvocationLimitsDTO limits,
        int maximumDecodedBytes, Instant notAfter, Consumer<String> partialResponse) {
        if (target == null || !"https://workspace-browser.invalid".equals(target.connection().baseUrl())
            || !"workspace-browser-fixture".equals(target.model().modelId()) || !"workspace-browser-not-real".equals(target.credential())) throw unsupported();
        AiMessage response;
        if (request.messages().getLast() instanceof ToolExecutionResultMessage result && "connection_probe".equals(result.toolName())) {
            response = AiMessage.from(result.text());
        } else if (request.messages().size() == 1 && request.messages().getFirst() instanceof UserMessage user) {
            String prompt = user.singleText();
            if (request.toolSpecifications().isEmpty() && prompt.startsWith("This is a connection check.")) {
                response = AiMessage.from(prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip());
            } else if (request.toolSpecifications().size() == 1 && "connection_probe".equals(request.toolSpecifications().getFirst().name())
                && prompt.startsWith("This is a harmless tool compatibility check.")) {
                String[] parts = prompt.split("\"");
                if (parts.length < 3 || !parts[1].matches("[0-9a-f-]{36}")) throw unsupported();
                response = AiMessage.from(ToolExecutionRequest.builder().id("workspace-browser-probe").name("connection_probe")
                    .arguments("{\"nonce\":\"" + parts[1] + "\"}").build());
            } else throw unsupported();
        } else throw unsupported();
        int bytes = ChatMessageSerializer.messageToJson(response).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumDecodedBytes) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
        return new ModelCallResultDTO(ChatResponse.builder().aiMessage(response).build(), bytes);
    }

    @Override
    public ModelCallResultDTO call(ModelInvocationDTO target, ChatRequest request, ModelInvocationLimitsDTO limits,
        int maximumDecodedBytes, Instant notAfter, Consumer<String> partialResponse, Consumer<String> partialThinking) {
        return call(target, request, limits, maximumDecodedBytes, notAfter, partialResponse);
    }

    private BusinessException unsupported() { return new BusinessException(ModelAccessErrorCode.MODEL_PROTOCOL_UNSUPPORTED); }
}
