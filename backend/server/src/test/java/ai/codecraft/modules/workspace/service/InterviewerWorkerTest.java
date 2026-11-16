package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDefinitionDTO;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.platformagent.service.PlatformDefinitions;
import ai.codecraft.modules.workspace.model.dto.InterviewerDispatchDTO;
import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterviewerWorkerTest {
    @Test
    void boundPlatformModelReceivesOnlyPublicRequirementsAndCannotCallTools() throws Exception {
        var store = mock(InterviewerStore.class);
        var bindings = mock(PlatformBindingService.class);
        var definitions = mock(PlatformDefinitions.class);
        var driver = mock(ModelCallDriver.class);
        var usage = mock(ModelUsageAccounting.class);
        var problems = mock(ProblemService.class);
        var workspace = mock(WorkspaceCoordinationStore.class);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var model = new ModelConfigurationResponse("m", "c", "Platform", "model", 8192, 2048, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_CONFIGURATION", false);
        var target = new ModelInvocationDTO(null, model, "platform-secret");
        var version = new PlatformVersionEntity("version", PlatformRole.INTERVIEWER, "definition", "PUBLISHED", 1, 1, "key", "nonce", "ciphertext", now, now);
        var definition = new PlatformDefinitionDTO(null, model, "Bound platform instruction", List.of(), "INTERVIEW_V1");
        when(store.claim("task")).thenReturn(new InterviewerDispatchDTO("task", "exercise", "What must I implement?", "READY", version, now.plusSeconds(120)), null);
        when(bindings.invocation(version)).thenReturn(target);
        when(definitions.decode("definition")).thenReturn(definition);
        when(definitions.instructions(PlatformRole.INTERVIEWER, definition)).thenReturn("Bound platform instruction");
        when(workspace.exercise("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "instance", "prepare", "READY", 1L, "[]", 1, now, null, null, null, null, null));
        when(problems.describe("problem")).thenReturn(new ProblemResponse("problem", "cart", "1.0.0", "Cart", "Public base requirements", 2700, List.of(), Map.of(), "digest", "image", "linux/amd64", "LOCAL", true, false));
        when(usage.price(target, now)).thenReturn("{\"price\":null}");
        when(store.recordPrice("task", "{\"price\":null}")).thenReturn(true);
        when(driver.call(eq(target), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Implement the published requirements.")).build(), 64));
        var worker = new InterviewerWorker(store, bindings, definitions, driver, usage, problems, workspace, Clock.fixed(now, ZoneOffset.UTC),
            new PlatformAgentProperties(1, 120, 300, 3, 300, 1800, 30));
        try {
            worker.run("task");
            worker.run("task");
            var request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(driver).call(eq(target), request.capture(), any(), eq(16384), eq(now.plusSeconds(120)));
            assertThat(request.getValue().parameters()).isExactlyInstanceOf(DefaultChatRequestParameters.class);
            assertThat(request.getValue().parameters().toolSpecifications()).isNullOrEmpty();
            assertThat(ChatMessageSerializer.messagesToJson(request.getValue().messages()))
                .contains("Public base requirements", "Bound platform instruction", "What must I implement?")
                .doesNotContain("platform-secret", "ciphertext");
            verify(store).finish("task", "Implement the published requirements.", null, null);
            verify(bindings, never()).bind(any());
        } finally { worker.close(); }
    }
}
