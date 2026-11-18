package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.infrastructure.tasks.TaskWakeup;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDefinitionDTO;
import ai.codecraft.modules.platformagent.model.dto.PlatformBindingDTO;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.platformagent.service.PlatformDefinitions;
import ai.codecraft.modules.workspace.model.dto.InterviewerDispatchDTO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
        var requirements = mock(ExerciseRequirementsService.class);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var model = new ModelConfigurationResponse("m", "c", "Platform", "model", 8192, 2048, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_CONFIGURATION", false);
        var target = new ModelInvocationDTO(null, model, "platform-secret");
        var version = new PlatformBindingDTO("version", "definition", "key", "nonce", "ciphertext");
        var definition = new PlatformDefinitionDTO(null, model, "Bound platform instruction", List.of(), "INTERVIEW_V1");
        when(store.claim("task")).thenReturn(new InterviewerDispatchDTO("task", "exercise", "What must I implement?", "READY", "BASE", 0L, "CLARIFY", "general", 1, "session", version, now.plusSeconds(120)), null);
        when(bindings.invocation(version)).thenReturn(target);
        when(definitions.decode("definition")).thenReturn(definition);
        when(definitions.instructions(PlatformRole.INTERVIEWER, definition)).thenReturn("Bound platform instruction");
        when(requirements.published("exercise")).thenReturn("Public base requirements");
        when(usage.price(target, now)).thenReturn("{\"price\":null}");
        when(store.recordPrice("task", "{\"price\":null}")).thenReturn(true);
        when(driver.call(eq(target), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Implement the published requirements.")).build(), 64));
        var worker = new InterviewerWorker(store, bindings, definitions, driver, usage, requirements, Clock.fixed(now, ZoneOffset.UTC),
            new PlatformAgentProperties(1, 120, 300, 3, 300, 1800, 30), mock(InterviewerContextCollector.class));
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
            when(store.claim("expired")).thenThrow(new ai.codecraft.common.exception.BusinessException(ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode.MODEL_CHECK_REQUIRED));
            worker.run("expired");
            verify(store).rejectQueued("expired", "MODEL_CHECK_REQUIRED");
        } finally { worker.close(); }
    }
    @Test
    void wakeupOnlyDiscoversItsOwnLane() {
        var worker = mock(InterviewerWorker.class);
        doCallRealMethod().when(worker).wake(any());
        worker.wake(new TaskWakeup(TaskWakeup.Lane.COPILOT));
        verify(worker, never()).discover();
        worker.wake(new TaskWakeup(TaskWakeup.Lane.INTERVIEWER));
        verify(worker).discover();
    }
}
