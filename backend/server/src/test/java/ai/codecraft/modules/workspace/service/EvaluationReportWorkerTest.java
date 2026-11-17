package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.platformagent.service.PlatformDefinitions;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationReportWorkerTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ExerciseReportStore store = mock(ExerciseReportStore.class);
    private final ReportEvidenceCollector collector = mock(ReportEvidenceCollector.class);
    private final PlatformBindingService bindings = mock(PlatformBindingService.class);
    private final PlatformDefinitions definitions = mock(PlatformDefinitions.class);
    private final ModelCallDriver driver = mock(ModelCallDriver.class);
    private final ModelUsageAccounting usage = mock(ModelUsageAccounting.class);
    private final ExerciseReportEntity ticket = new ExerciseReportEntity("exercise", "report", "owner", "session", "version", 7, "UNAVAILABLE", null,
        true, "", "operation", 1, "PREPARING", 0, "attempt", now.plusSeconds(120), null, null, null, now, now);
    private final ModelConfigurationResponse model = new ModelConfigurationResponse("m", "c", "Platform", "model", 8192, 2048, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_CONFIGURATION", false);
    private final ModelInvocationDTO target = new ModelInvocationDTO(null, model, "platform-secret");
    @BeforeEach
    void prepare() {
        var version = new PlatformVersionEntity("version", PlatformRole.EVALUATOR, "definition", "PUBLISHED", 1, 1, "key", "nonce", "ciphertext", now, now);
        var definition = new PlatformDefinitionDTO(null, model, "Bound evaluator", List.of(), "INTERVIEW_V1");
        when(store.prepare("exercise")).thenReturn(ticket);
        when(collector.collect(ticket)).thenReturn(List.of());
        when(bindings.version("exercise", PlatformRole.EVALUATOR)).thenReturn(version);
        when(bindings.invocation(version)).thenReturn(target);
        when(definitions.decode("definition")).thenReturn(definition);
        when(definitions.instructions(PlatformRole.EVALUATOR, definition)).thenReturn("Bound evaluator");
        when(store.encode(List.of())).thenReturn("[]");
        when(usage.price(target, now)).thenReturn("{}");
        when(store.dispatch(ticket, List.of(), "{}")).thenReturn(now.plusSeconds(120));
    }
    private EvaluationReportWorker worker() {
        return new EvaluationReportWorker(store, collector, new EvaluationReportValidator(new ObjectMapper()), bindings, definitions, driver, usage,
            Clock.fixed(now, ZoneOffset.UTC), new PlatformAgentProperties(1, 120, 300, 3, 300, 1800, 30));
    }
    @Test
    void invalidCompletedResponseRequestsBoundedRegenerationWithNoTools() throws Exception {
        when(driver.call(eq(target), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("not JSON")).build(), 8));
        var worker = worker();
        try {
            worker.run("exercise");
            verify(store).complete(ticket, null, "not JSON", null, "EVALUATION_INVALID_RESPONSE", true);
            var request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(driver).call(eq(target), request.capture(), any(), eq(65536), eq(now.plusSeconds(120)));
            assertThat(request.getValue().parameters().toolSpecifications()).isNullOrEmpty();
            assertThat(ChatMessageSerializer.messagesToJson(request.getValue().messages())).contains("Bound evaluator", "UNAVAILABLE", "frozen revision: 7", "TIMELINE", "CONTEXT_TRACE", "Never mechanically penalize")
                .doesNotContain("platform-secret", "ciphertext", "session");
        } finally { worker.close(); }
    }
    @Test
    void uncertainDriverFailureDoesNotRequestAutomaticRegeneration() throws Exception {
        when(driver.call(eq(target), any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("unknown network outcome"));
        var worker = worker();
        try {
            worker.run("exercise");
            verify(store).complete(ticket, null, null, null, "EVALUATION_RESULT_UNKNOWN", false);
        } finally { worker.close(); }
    }
    @Test
    void authorizationLossAtDispatchPreventsThePaidCall() throws Exception {
        when(store.dispatch(ticket, List.of(), "{}")).thenReturn(null);
        var worker = worker();
        try {
            worker.run("exercise");
            verify(driver, never()).call(any(), any(), any(), anyInt(), any());
            verify(store, never()).complete(eq(ticket), any(), any(), any(), any(), eq(true));
        } finally { worker.close(); }
    }
    @Test
    void platformFaultCanPublishAnHonestUnscoredReport() throws Exception {
        String raw = """
            {"verification":"UNAVAILABLE","total":null,"conclusion":"平台验收不可用，缺少足够证据。","dimensions":[
            {"key":"CORRECTNESS","score":null,"feedback":"等待可靠验收。","evidenceIds":[]},
            {"key":"QUALITY","score":null,"feedback":"证据不足。","evidenceIds":[]},
            {"key":"VALIDATION","score":null,"feedback":"证据不足。","evidenceIds":[]},
            {"key":"COLLABORATION","score":null,"feedback":"证据不足。","evidenceIds":[]}]}
            """;
        when(driver.call(eq(target), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(raw)).build(), 512));
        var worker = worker();
        try {
            worker.run("exercise");
            verify(store).complete(eq(ticket), any(), eq(raw), isNull(), isNull(), eq(false));
        } finally { worker.close(); }
    }
    @Test
    void wakeupOnlyDiscoversItsOwnLane() {
        var worker = mock(EvaluationReportWorker.class);
        doCallRealMethod().when(worker).wake(any());
        worker.wake(new TaskWakeup(TaskWakeup.Lane.COPILOT));
        verify(worker, never()).discover();
        worker.wake(new TaskWakeup(TaskWakeup.Lane.EVALUATION));
        verify(worker).discover();
    }
}
