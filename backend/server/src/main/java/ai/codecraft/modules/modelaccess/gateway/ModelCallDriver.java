package ai.codecraft.modules.modelaccess.gateway;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.exception.HttpException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import dev.langchain4j.model.output.FinishReason;

/** One bounded SDK request. Call only outside a database transaction, from an owned worker. */
@Component
public class ModelCallDriver implements AutoCloseable {
    private final ModelSdkClientFactory factory;
    private final ModelCallProperties properties;
    private final Semaphore capacity;
    private final Clock clock;
    private final ConcurrentMap<BoundedModelHttpClientBuilder, ResponseSubscriber> transports = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    public ModelCallDriver(ModelSdkClientFactory factory, ModelCallProperties properties, Clock clock) {
        this.factory = factory; this.properties = properties; this.clock = clock; capacity = new Semaphore(properties.maxConcurrent());
    }

    public ModelCallResultDTO call(ModelInvocationDTO target, ChatRequest request, ModelInvocationLimitsDTO limits, int maximumDecodedBytes) throws InterruptedException {
        return call(target, request, limits, maximumDecodedBytes, clock.instant().plus(properties.timeout()));
    }

    public ModelCallResultDTO call(ModelInvocationDTO target, ChatRequest request, ModelInvocationLimitsDTO limits, int maximumDecodedBytes, Instant notAfter) throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Model calls must run outside database transactions");
        requestParameters(request);
        if (maximumDecodedBytes < 1 || maximumDecodedBytes > properties.maxDecodedBytes()) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        if (notAfter == null) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        var authorizedDuration = Duration.between(clock.instant(), notAfter);
        if (authorizedDuration.isNegative() || authorizedDuration.isZero()) throw new BusinessException(ModelAccessErrorCode.MODEL_REQUEST_TIMEOUT);
        long budgetNanos = (authorizedDuration.compareTo(properties.timeout()) < 0 ? authorizedDuration : properties.timeout()).toNanos();
        reapClosedTransports();
        if (closed.get() || !capacity.tryAcquire()) throw new BusinessException(ModelAccessErrorCode.MODEL_RUNTIME_BUSY);
        long started = System.nanoTime();
        var subscriber = new ResponseSubscriber(maximumDecodedBytes);
        BoundedModelHttpClientBuilder transport = null;
        try {
            transport = new BoundedModelHttpClientBuilder(properties.responseBytes(), properties.errorBytes());
            transports.put(transport, subscriber);
            if (closed.get()) throw new BusinessException(ModelAccessErrorCode.MODEL_RUNTIME_BUSY);
            var model = factory.create(target, limits, transport);
            if (remaining(started, budgetNanos, notAfter) <= 0) throw new TimeoutException("Model request deadline elapsed");
            model.chat(request).subscribe(subscriber);
            long remaining = remaining(started, budgetNanos, notAfter);
            if (remaining <= 0) throw new TimeoutException("Model request deadline elapsed");
            var response = subscriber.result.get(remaining, TimeUnit.NANOSECONDS);
            if (response.finishReason() == FinishReason.LENGTH) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
            int bytes = ChatMessageSerializer.messagesToJson(List.of(response.aiMessage())).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maximumDecodedBytes) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
            if (remaining(started, budgetNanos, notAfter) <= 0) throw new TimeoutException("Model request deadline elapsed");
            return new ModelCallResultDTO(response, Math.max(bytes, subscriber.streamedBytes));
        } catch (TimeoutException timeout) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_REQUEST_TIMEOUT, timeout);
        } catch (ExecutionException failure) {
            throw failure(failure.getCause());
        } catch (RuntimeException failure) {
            throw failure(failure);
        } finally {
            subscriber.cancel();
            if (transport == null) capacity.release();
            else { transport.close(); reapClosedTransports(); }
        }
    }

    private long remaining(long started, long budgetNanos, Instant notAfter) {
        var authorized = Duration.between(clock.instant(), notAfter);
        if (authorized.isNegative() || authorized.isZero()) return 0;
        long wallRemaining = authorized.compareTo(properties.timeout()) < 0 ? authorized.toNanos() : properties.timeout().toNanos();
        return Math.min(wallRemaining, budgetNanos - (System.nanoTime() - started));
    }

    @Scheduled(fixedDelay = 250)
    public void reapClosedTransports() {
        for (var entry : transports.entrySet()) {
            if (entry.getKey().isTerminated() && transports.remove(entry.getKey(), entry.getValue())) capacity.release();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        closed.set(true);
        for (var entry : transports.entrySet()) { entry.getValue().cancel(); entry.getKey().close(); }
        reapClosedTransports();
    }

    private void requestParameters(ChatRequest request) {
        if (request == null || request.parameters() == null || request.parameters().getClass() != DefaultChatRequestParameters.class) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        }
        var parameters = request.parameters();
        // Per-call tools/format are permitted; identity, sampling and output limits belong only to the resolved configuration.
        if (parameters.modelName() != null || parameters.maxOutputTokens() != null || parameters.temperature() != null || parameters.topP() != null
            || parameters.topK() != null || parameters.frequencyPenalty() != null || parameters.presencePenalty() != null
            || parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
    }

    private BusinessException failure(Throwable failure) {
        ModelAccessErrorCode code = ModelAccessErrorCode.MODEL_INVALID_RESPONSE;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof BusinessException business) return business;
            if (current instanceof HttpException http) return new BusinessException(providerStatus(http.statusCode()), failure);
            if (current instanceof ModelResponseLimitException limit) return new BusinessException(limit.statusCode() >= 300
                ? providerStatus(limit.statusCode()) : ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED, failure);
            if (current instanceof HttpTimeoutException) return new BusinessException(ModelAccessErrorCode.MODEL_REQUEST_TIMEOUT, failure);
            if (current instanceof IOException) code = ModelAccessErrorCode.MODEL_NETWORK_UNAVAILABLE;
        }
        return new BusinessException(code, failure);
    }

    private ModelAccessErrorCode providerStatus(int status) {
        if (status >= 300 && status < 400) return ModelAccessErrorCode.MODEL_REDIRECT_REJECTED;
        if (status == 401 || status == 403) return ModelAccessErrorCode.MODEL_AUTHENTICATION_FAILED;
        if (status == 404) return ModelAccessErrorCode.MODEL_ENDPOINT_NOT_FOUND;
        if (status == 429) return ModelAccessErrorCode.MODEL_PROVIDER_RATE_LIMITED;
        if (status >= 400 && status < 500) return ModelAccessErrorCode.MODEL_PROVIDER_REQUEST_REJECTED;
        return ModelAccessErrorCode.MODEL_PROVIDER_UNAVAILABLE;
    }

    private static final class ResponseSubscriber implements Flow.Subscriber<ChatModelStreamingEvent> {
        private final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private ChatResponse response;
        private final int maximum;
        private int streamedBytes;
        ResponseSubscriber(int maximum) { this.maximum = maximum; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value) || cancelled.get()) value.cancel();
            else value.request(Long.MAX_VALUE);
        }
        @Override public void onNext(ChatModelStreamingEvent event) {
            if (result.isDone()) return;
            String text = switch (event) {
                case PartialResponse partial -> partial.text();
                case PartialThinking thinking -> thinking.text();
                case PartialToolCall tool -> tool.partialArguments();
                default -> null;
            };
            if (text != null) {
                int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > maximum - streamedBytes) {
                    result.completeExceptionally(new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED)); cancel(); return;
                }
                streamedBytes += bytes;
            }
            if (event instanceof CompleteResponse complete) response = complete.chatResponse();
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() {
            if (response == null || response.aiMessage() == null) result.completeExceptionally(new BusinessException(ModelAccessErrorCode.MODEL_INVALID_RESPONSE));
            else result.complete(response);
        }
        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                result.completeExceptionally(new BusinessException(ModelAccessErrorCode.MODEL_CALL_CANCELLED));
                var current = subscription.get();
                if (current != null) current.cancel();
            }
        }
    }
}
