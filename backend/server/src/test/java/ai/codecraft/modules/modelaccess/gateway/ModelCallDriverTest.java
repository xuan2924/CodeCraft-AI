package ai.codecraft.modules.modelaccess.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import ai.codecraft.common.exception.BusinessException;
import dev.langchain4j.model.chat.response.PartialResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.output.FinishReason;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ModelCallDriverTest {
    private final ModelSdkClientFactory factory = mock(ModelSdkClientFactory.class);
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final ModelInvocationLimitsDTO limits = new ModelInvocationLimitsDTO(128, null);
    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("fixture")).build();

    @Test
    void completeSdkResponseIsReturnedAfterExactlyOneSubscriptionWithoutInventingUsage() throws Exception {
        var expected = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        model(subscriber -> { subscriber.onNext(new CompleteResponse(expected)); subscriber.onComplete(); });
        var result = driver(Duration.ofSeconds(1)).call(target(), request, limits, 4096);
        assertEquals(expected, result.response());
        assertEquals(null, result.response().tokenUsage());
        assertTrue(result.decodedBytes() > 0);
        assertEquals(1, subscriptions.get());
    }

    @Test
    void streamedOutputCannotBypassTheBudgetByEndingWithASmallCompleteMessage() {
        model(subscriber -> {
            subscriber.onNext(new PartialResponse("x".repeat(512)));
            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build()));
            subscriber.onComplete();
        });
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofSeconds(1)).call(target(), request, limits, 128));
        assertEquals("MODEL_OUTPUT_LIMIT_EXCEEDED", failure.code());
        assertEquals(1, subscriptions.get()); assertTrue(cancellations.get() > 0);
    }

    @Test
    void finalOpaqueProtocolFieldsAreCountedEvenWithoutPartialCallbacks() {
        var message = AiMessage.builder().text("ok").attributes(Map.of("encrypted_reasoning", "x".repeat(512))).build();
        model(subscriber -> { subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(message).build())); subscriber.onComplete(); });
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofSeconds(1)).call(target(), request, limits, 128));
        assertEquals("MODEL_OUTPUT_LIMIT_EXCEEDED", failure.code());
    }

    @Test
    void wallDeadlineIncludesSubscriptionAndDoesNotAcceptAnAlreadyLateCompleteResponse() {
        model(subscriber -> {
            LockSupport.parkNanos(Duration.ofMillis(60).toNanos());
            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("late")).build())); subscriber.onComplete();
        });
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofMillis(15)).call(target(), request, limits, 4096));
        assertEquals("MODEL_REQUEST_TIMEOUT", failure.code());
    }

    @Test
    void silentStreamTimesOutAndCancelsBeforeAnyTokenWithoutRetry() {
        model(subscriber -> { });
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofMillis(50)).call(target(), request, limits, 4096));
        assertEquals("MODEL_REQUEST_TIMEOUT", failure.code());
        assertEquals(1, subscriptions.get()); assertTrue(cancellations.get() > 0);
    }

    @Test
    void subscriptionArrivingAfterTimeoutIsCancelledWithoutStartingDemand() {
        var late = new AtomicReference<Flow.Subscriber<? super ChatModelStreamingEvent>>();
        var demand = new AtomicInteger();
        StreamingChatModel delayed = new StreamingChatModel() {
            @Override public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest input) { return late::set; }
        };
        when(factory.create(any(), any(), any())).thenReturn(delayed);
        assertThrows(BusinessException.class, () -> driver(Duration.ofMillis(50)).call(target(), request, limits, 4096));
        late.get().onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { demand.incrementAndGet(); }
            @Override public void cancel() { cancellations.incrementAndGet(); }
        });
        assertEquals(0, demand.get()); assertEquals(1, cancellations.get());
    }

    @ParameterizedTest
    @CsvSource({"302,MODEL_REDIRECT_REJECTED", "401,MODEL_AUTHENTICATION_FAILED", "403,MODEL_AUTHENTICATION_FAILED", "404,MODEL_ENDPOINT_NOT_FOUND", "400,MODEL_PROVIDER_REQUEST_REJECTED", "422,MODEL_PROVIDER_REQUEST_REJECTED", "429,MODEL_PROVIDER_RATE_LIMITED", "503,MODEL_PROVIDER_UNAVAILABLE"})
    void providerErrorsAreClassifiedWithoutReturningTheirBodyOrRetrying(int status, String code) {
        model(subscriber -> subscriber.onError(new CompletionException(new HttpException(status, "fixture-private-provider-body"))));
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofSeconds(1)).call(target(), request, limits, 4096));
        assertEquals(code, failure.code());
        assertFalse(failure.getMessage().contains("fixture-private-provider-body"));
        assertEquals(1, subscriptions.get());
    }

    @Test
    void runtimeCapacityDoesNotQueueOrDispatchAnotherPaidRequest() throws Exception {
        var entered = new CountDownLatch(1);
        model(subscriber -> entered.countDown());
        var driver = driver(Duration.ofSeconds(1));
        try (var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1))) {
            var first = worker.submit(() -> driver.call(target(), request, limits, 4096));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var failure = assertThrows(BusinessException.class, () -> driver.call(target(), request, limits, 4096));
                assertEquals("MODEL_RUNTIME_BUSY", failure.code());
                assertEquals(1, subscriptions.get());
            } finally { first.cancel(true); }
        }
    }

    @Test
    void truncatedProviderResponseCannotBecomeAnExecutableToolBatch() {
        model(subscriber -> {
            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("partial")).finishReason(FinishReason.LENGTH).build()));
            subscriber.onComplete();
        });
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofSeconds(1)).call(target(), request, limits, 4096));
        assertEquals("MODEL_OUTPUT_LIMIT_EXCEEDED", failure.code());
    }

    @Test
    void modelDispatchInsideADatabaseTransactionIsRejectedBeforeSubscription() {
        model(subscriber -> { });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> driver(Duration.ofSeconds(1)).call(target(), request, limits, 4096));
            assertEquals(0, subscriptions.get());
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test
    void shutdownCompletesTheWaitingCallEvenWhenSdkCancellationHasNoTerminalCallback() throws Exception {
        var entered = new CountDownLatch(1);
        model(subscriber -> entered.countDown());
        var driver = driver(Duration.ofSeconds(1));
        try (var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1))) {
            var call = worker.submit(() -> driver.call(target(), request, limits, 4096));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS)); driver.close();
                var failure = assertThrows(ExecutionException.class, () -> call.get(200, TimeUnit.MILLISECONDS));
                assertEquals("MODEL_CALL_CANCELLED", ((BusinessException) failure.getCause()).code());
                assertTrue(cancellations.get() > 0);
            } finally { call.cancel(true); driver.close(); }
        }
    }

    @Test
    void perRequestParametersCannotOverrideTheSelectedModelOrProbeOutputLimit() {
        model(subscriber -> {
            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build())); subscriber.onComplete();
        });
        var override = ChatRequest.builder().messages(UserMessage.from("fixture"))
            .parameters(ChatRequestParameters.builder().modelName("another-model").maxOutputTokens(1000000).build()).build();
        var failure = assertThrows(BusinessException.class, () -> driver(Duration.ofSeconds(1)).call(target(), override, limits, 4096));
        assertEquals("MODEL_INVALID_REQUEST", failure.code()); assertEquals(0, subscriptions.get());
    }

    private ModelCallDriver driver(Duration timeout) {
        return new ModelCallDriver(factory, new ModelCallProperties(timeout, Duration.ofMillis(1), 1, 8192, 1024, 4096));
    }
    private void model(Consumer<Flow.Subscriber<? super ChatModelStreamingEvent>> source) {
        StreamingChatModel model = new StreamingChatModel() {
            @Override public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest input) {
                return subscriber -> {
                    subscriptions.incrementAndGet();
                    subscriber.onSubscribe(new Flow.Subscription() {
                        private boolean requested;
                        @Override public void request(long count) { if (!requested) { requested = true; source.accept(subscriber); } }
                        @Override public void cancel() { cancellations.incrementAndGet(); }
                    });
                };
            }
        };
        when(factory.create(any(), any(), any())).thenReturn(model);
    }
    private ModelInvocationDTO target() {
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://example.test/v1", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "fixture-model", 100000, 4096, ModelThinkingMode.OFF, null, null, null, "1", "1", "USER_PROVIDED", false);
        return new ModelInvocationDTO(connection, model, "fixture-not-a-real-key");
    }
}
