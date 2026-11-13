package ai.codecraft.modules.modelaccess.gateway;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Decorates JDK response delivery, including SDK error readers and incremental SSE parsers. */
final class BoundedModelHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final long successBytes;
    private final long errorBytes;
    BoundedModelHttpClient(HttpClient delegate, long successBytes, long errorBytes) {
        this.delegate = delegate; this.successBytes = successBytes; this.errorBytes = errorBytes;
    }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return delegate.send(request, bounded(handler));
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        // Return the native future, not a dependent stage which might swallow cancellation.
        return delegate.sendAsync(request, bounded(handler));
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushes) {
        return delegate.sendAsync(request, bounded(handler), (original, promised, acceptor) ->
            pushes.applyPushPromise(original, promised, responseHandler -> acceptor.apply(bounded(responseHandler))));
    }
    @Override public WebSocket.Builder newWebSocketBuilder() { return delegate.newWebSocketBuilder(); }
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public void shutdownNow() { delegate.shutdownNow(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
    @Override public void close() { delegate.close(); }

    private <T> HttpResponse.BodyHandler<T> bounded(HttpResponse.BodyHandler<T> handler) {
        return response -> new LimitedSubscriber<>(handler.apply(response), response.statusCode(),
            response.statusCode() >= 200 && response.statusCode() < 300 ? successBytes : errorBytes);
    }

    private static final class LimitedSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final int status;
        private final long maximum;
        private Flow.Subscription upstream;
        private long bytes;
        private boolean done;
        LimitedSubscriber(HttpResponse.BodySubscriber<T> delegate, int status, long maximum) {
            this.delegate = delegate; this.status = status; this.maximum = maximum;
        }
        @Override public CompletionStage<T> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            upstream = subscription; delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes) {
                    done = true; upstream.cancel(); delegate.onError(new ModelResponseLimitException(status)); return;
                }
                bytes += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { if (!done) { done = true; delegate.onError(failure); } }
        @Override public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
}
