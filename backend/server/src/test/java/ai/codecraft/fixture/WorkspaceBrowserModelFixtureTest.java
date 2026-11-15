package ai.codecraft.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelSdkClientFactory;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.service.ModelCheckPrompts;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkspaceBrowserModelFixtureTest {
    @Test
    void builtInPrerequisitesUseTheRealPromptContractWithoutAnyProviderClient() throws Exception {
        var factory = mock(ModelSdkClientFactory.class);
        var properties = new ModelCallProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1, 65536, 4096, 32768);
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://workspace-browser.invalid", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "workspace-browser-fixture", 100000, 1024, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_PROVIDED", false);
        var target = new ModelInvocationDTO(connection, model, "workspace-browser-not-real");
        var limits = new ModelInvocationLimitsDTO(256, null); var prompts = new ModelCheckPrompts();
        try (var fixture = new WorkspaceBrowserModelFixture(factory, properties, Clock.systemUTC())) {
            var basic = ChatRequest.builder().messages(prompts.basic("expected-marker")).build();
            assertThat(fixture.call(target, basic, limits, 32768).response().aiMessage().text()).isEqualTo("expected-marker");
            assertThat(fixture.call(target, basic, limits, 32768, Clock.systemUTC().instant().plusSeconds(5), ignored -> {}, ignored -> {})
                .response().aiMessage().text()).isEqualTo("expected-marker");
            var tool = ChatRequest.builder().messages(prompts.tool("00000000-0000-0000-0000-000000000001"))
                .toolSpecifications(ToolSpecification.builder().name("connection_probe").build()).build();
            var assistant = fixture.call(target, tool, limits, 32768).response().aiMessage();
            assertThat(assistant.toolExecutionRequests()).singleElement().satisfies(call -> assertThat(call.arguments()).contains("00000000-0000-0000-0000-000000000001"));
            var continuation = ChatRequest.builder().messages(tool.messages().getFirst(), assistant,
                ToolExecutionResultMessage.from(assistant.toolExecutionRequests().getFirst(), "receipt-marker")).build();
            assertThat(fixture.call(target, continuation, limits, 32768).response().aiMessage().text()).isEqualTo("receipt-marker");
            assertThatThrownBy(() -> fixture.call(new ModelInvocationDTO(connection, model, "not-the-fixture"), basic, limits, 32768)).hasMessage("MODEL_PROTOCOL_UNSUPPORTED");
        }
        verifyNoInteractions(factory);
    }
}
