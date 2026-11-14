package ai.codecraft.modules.modelaccess.gateway;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** JDK transport hook for the native SDK; never changes a provider's wire protocol. */
public final class BoundedModelHttpClientBuilder implements HttpClient.Builder, AutoCloseable {
    private final HttpClient.Builder delegate = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER);
    private final List<HttpClient> clients = new ArrayList<>();
    private final long successBytes;
    private final long errorBytes;
    private boolean closed;

    public BoundedModelHttpClientBuilder(long successBytes, long errorBytes) {
        if (successBytes < 1 || errorBytes < 1) throw new IllegalArgumentException("Invalid model response limit");
        this.successBytes = successBytes; this.errorBytes = errorBytes;
    }
    @Override public BoundedModelHttpClientBuilder cookieHandler(CookieHandler value) { delegate.cookieHandler(value); return this; }
    @Override public BoundedModelHttpClientBuilder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
    @Override public BoundedModelHttpClientBuilder sslContext(SSLContext value) { delegate.sslContext(value); return this; }
    @Override public BoundedModelHttpClientBuilder sslParameters(SSLParameters value) { delegate.sslParameters(value); return this; }
    @Override public BoundedModelHttpClientBuilder executor(Executor value) { delegate.executor(value); return this; }
    @Override public BoundedModelHttpClientBuilder followRedirects(HttpClient.Redirect value) {
        if (value != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Model redirects are disabled");
        delegate.followRedirects(value); return this;
    }
    @Override public BoundedModelHttpClientBuilder version(HttpClient.Version value) { delegate.version(value); return this; }
    @Override public BoundedModelHttpClientBuilder priority(int value) { delegate.priority(value); return this; }
    @Override public BoundedModelHttpClientBuilder proxy(ProxySelector value) { delegate.proxy(value); return this; }
    @Override public BoundedModelHttpClientBuilder authenticator(Authenticator value) { delegate.authenticator(value); return this; }
    @Override public BoundedModelHttpClientBuilder localAddress(InetAddress value) { delegate.localAddress(value); return this; }
    @Override public synchronized HttpClient build() {
        if (closed) throw new IllegalStateException("Model transport is closed");
        HttpClient client = delegate.build();
        clients.add(client);
        return new BoundedModelHttpClient(client, successBytes, errorBytes);
    }
    @Override public synchronized void close() {
        closed = true;
        for (HttpClient client : clients) client.shutdownNow();
    }
    /** Physical JDK transport convergence, not merely cancellation being requested. */
    public synchronized boolean isTerminated() { return closed && clients.stream().allMatch(HttpClient::isTerminated); }
}
