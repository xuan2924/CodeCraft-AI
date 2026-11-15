package ai.codecraft.modules.modelaccess.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ai.codecraft.common.exception.BusinessException;

import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelProbeProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckDispatchDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelCheckRunnerTest {
    private final ModelCheckExecutionStore store = mock(ModelCheckExecutionStore.class);
    private final ModelCallDriver driver = mock(ModelCallDriver.class);
    private ModelInvocationDTO target;
    private final ModelCheckRunner runner = new ModelCheckRunner(store, driver, new ModelCheckPrompts(), new ModelProbeProperties(128, 256, 2048),
        new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144), new ObjectMapper());

    @BeforeEach
    void setup() {
        when(store.firstOrdinal("check")).thenReturn(1);
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://example.test", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "fixture-model", 100000, 4096, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_PROVIDED", false);
        target = new ModelInvocationDTO(connection, model, "fixture-not-a-real-key");
        when(store.begin(eq("check"), anyInt(), anyString())).thenAnswer(call -> {
            int ordinal = call.getArgument(1);
            return new ModelCheckDispatchDTO("request-" + ordinal, "check", ordinal, Instant.parse("2030-01-01T00:00:00Z"), 65536, target);
        });
        when(store.complete(anyString(), anyString(), anyInt(), any())).thenReturn(true);
        when(store.recordUsage(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void ordinaryHttpSuccessWithWrongMarkerDoesNotPassBasicCheckOrTriggerMoreCalls() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(result(AiMessage.from("not the requested marker")));
        runner.run("check");
        verify(store).complete(eq("request-1"), anyString(), eq(100), eq(ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
        verify(store, never()).begin(eq("check"), eq(2), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nonce\":\"wrong\"}", "{\"nonce\":\"{{nonce}}\",\"extra\":true}", "{\"nonce\":\"wrong\",\"nonce\":\"{{nonce}}\"}", "{\"nonce\":\"{{nonce}}\"} {}", "[]"})
    void malformedOrUnboundToolArgumentsCannotTriggerTheThirdPaidRequest(String parameters) throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) return result(AiMessage.from(prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip()));
            String nonce = prompt.split("\"")[1];
            var tool = ToolExecutionRequest.builder().id("tool-1").name("connection_probe").arguments(parameters.replace("{{nonce}}", nonce)).build();
            return result(AiMessage.builder().toolExecutionRequests(List.of(tool)).build());
        });
        runner.run("check");
        verify(store).complete(eq("request-2"), anyString(), eq(100), eq(ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
        verify(store, never()).begin(eq("check"), eq(3), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exact", "wrong", "padded", "reuse-basic"})
    void exactToolReceiptAndNativeReasoningHistoryAreRequiredForTheThreeRequestRoundtrip(String mode) throws Exception {
        boolean reuseBasic = mode.equals("reuse-basic");
        if (reuseBasic) when(store.firstOrdinal("check")).thenReturn(2);
        var calls = new AtomicInteger();
        var original = new AtomicReference<AiMessage>();
        var challenge = new AtomicReference<String>();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            int ordinal = calls.incrementAndGet() + (reuseBasic ? 1 : 0);
            ChatRequest request = call.getArgument(1);
            ModelInvocationLimitsDTO limits = call.getArgument(2);
            assertEquals(ordinal == 1 ? 128 : 256, limits.outputTokens());
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"), call.getArgument(4));
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            if (ordinal == 1) return result(AiMessage.from(prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip()));
            if (ordinal == 2) {
                String nonce = prompt.split("\"")[1]; challenge.set(nonce);
                var tool = ToolExecutionRequest.builder().id("tool-1").name("connection_probe").arguments("{\"nonce\":\"" + nonce + "\"}").build();
                original.set(AiMessage.builder().thinking("fixture reasoning").attributes(Map.of("signature", "opaque-fixture"))
                    .toolExecutionRequests(List.of(tool)).build());
                return result(original.get());
            }
            assertEquals(3, ordinal);
            assertSame(original.get(), request.messages().get(1));
            var receipt = (ToolExecutionResultMessage) request.messages().get(2);
            assertEquals("tool-1", receipt.id());
            assertEquals("connection_probe", receipt.toolName());
            assertFalse(prompt.contains(receipt.text()));
            assertNotEquals(challenge.get(), receipt.text());
            assertEquals(1, request.toolSpecifications().size());
            return result(AiMessage.from((mode.equals("exact") || reuseBasic) ? receipt.text() : mode.equals("padded") ? "\n" + receipt.text() + "\n" : challenge.get()));
        });
        runner.run("check");
        assertEquals(reuseBasic ? 2 : 3, calls.get());
        verify(store).complete(eq("request-3"), anyString(), eq(100), eq(mode.equals("exact") || reuseBasic ? null : ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
        verify(driver, times(reuseBasic ? 2 : 3)).call(any(), any(), any(), anyInt(), any());
        if (reuseBasic) verify(store, never()).begin(eq("check"), eq(1), anyString());
    }

    @Test
    void surroundingWhitespaceIsNotAnExactBasicMarker() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            String marker = prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip();
            return result(AiMessage.from("\n" + marker + "\n"));
        });
        runner.run("check");
        verify(store).complete(eq("request-1"), anyString(), eq(100), eq(ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
        verify(store, never()).begin(eq("check"), eq(2), anyString());
    }

    @Test
    void providerFailureIsRecordedOnceWithoutAutomaticRetry() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenThrow(new BusinessException(ModelAccessErrorCode.MODEL_PROVIDER_RATE_LIMITED));
        runner.run("check");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        verify(store).complete(eq("request-1"), anyString(), eq(0), eq(ModelAccessErrorCode.MODEL_PROVIDER_RATE_LIMITED));
        verify(store, never()).begin(eq("check"), eq(2), anyString());
    }

    @Test
    void interruptedWorkerPreservesTheUncertainRequestForRecoveryInsteadOfClaimingNoBilling() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenThrow(new InterruptedException("fixture"));
        try {
            runner.run("check");
            assertTrue(Thread.currentThread().isInterrupted());
            verify(store, never()).complete(anyString(), anyString(), anyInt(), any());
            verify(store, never()).begin(eq("check"), eq(2), anyString());
        } finally { Thread.interrupted(); }
    }

    @Test
    void anthropicThinkingCheckCapsOutputAndUsesASmallerValidThinkingBudget() throws Exception {
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://example.test", ModelProtocol.ANTHROPIC, ModelProviderPreset.CUSTOM, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "fixture-model", 100000, 4096, ModelThinkingMode.ON, null, 3000, null, "1", "1", "USER_PROVIDED", false);
        target = new ModelInvocationDTO(connection, model, "fixture-not-a-real-key");
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ModelInvocationLimitsDTO limits = call.getArgument(2);
            assertEquals(2048, limits.outputTokens()); assertEquals(1024, limits.thinkingBudgetTokens());
            return result(AiMessage.from("fixture wrong answer"));
        });
        runner.run("check");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"other_tool", "CONNECTION_PROBE"})
    void differentToolNameCannotPassAsTheRegisteredProbe(String name) throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) return result(AiMessage.from(prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip()));
            var tool = ToolExecutionRequest.builder().id("tool-1").name(name).arguments("{\"nonce\":\"" + prompt.split("\"")[1] + "\"}").build();
            return result(AiMessage.builder().toolExecutionRequests(List.of(tool)).build());
        });
        runner.run("check");
        verify(store).complete(eq("request-2"), anyString(), eq(100), eq(ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
        verify(store, never()).begin(eq("check"), eq(3), anyString());
    }

    private ModelCallResultDTO result(AiMessage message) { return new ModelCallResultDTO(ChatResponse.builder().aiMessage(message).build(), 100); }
}
