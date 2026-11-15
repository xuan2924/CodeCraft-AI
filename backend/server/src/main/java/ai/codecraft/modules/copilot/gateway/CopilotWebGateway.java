package ai.codecraft.modules.copilot.gateway;

import ai.codecraft.modules.copilot.config.CopilotWebProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotWebFetchDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Official Tavily service adapter. Does not fetch arbitrary URLs on the application host or retry paid calls. */
@Component
public class CopilotWebGateway implements AutoCloseable {
    private static final int RESPONSE_BYTES = 1024 * 1024;
    private final CopilotWebProperties properties;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Semaphore permits = new Semaphore(2);
    public CopilotWebGateway(CopilotWebProperties properties, @Qualifier("workspaceJson") ObjectMapper json) {
        this.properties = properties; this.json = json;
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.timeout()).build();
    }
    public CopilotWebFetchDTO call(String endpoint, Map<String, Object> input, Duration remaining) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Web request cancelled");
        if (properties.apiKey().isBlank()) return failed("WEB_NOT_CONFIGURED");
        if (!permits.tryAcquire()) return failed("WEB_BUSY");
        var oversized = new AtomicBoolean();
        try {
            if (remaining.isZero() || remaining.isNegative()) return failed("WEB_DEADLINE_EXCEEDED");
            Duration timeout = remaining.compareTo(properties.timeout()) < 0 ? remaining : properties.timeout();
            var request = HttpRequest.newBuilder(properties.baseUrl().resolve(endpoint)).timeout(timeout)
                .header("Authorization", "Bearer " + properties.apiKey()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
            var bytes = new ByteArrayOutputStream();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(value -> {
                if (value.length > RESPONSE_BYTES - bytes.size()) { oversized.set(true); throw new IllegalStateException("Web response exceeds byte limit"); }
                bytes.writeBytes(value);
            })));
            HttpResponse<Void> response;
            try { response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS); }
            catch (TimeoutException elapsed) { pending.cancel(true); return failed("WEB_TIMEOUT"); }
            catch (InterruptedException cancelled) { pending.cancel(true); throw cancelled; }
            if (response.statusCode() != 200) return failed(switch (response.statusCode()) {
                case 401, 403 -> "WEB_CREDENTIAL_REJECTED";
                case 429 -> "WEB_RATE_LIMITED";
                default -> "WEB_SERVICE_ERROR";
            });
            var body = json.readTree(bytes.toByteArray());
            if (body == null || !body.isObject()) return failed("WEB_INVALID_RESPONSE");
            return new CopilotWebFetchDTO("OK", body);
        } catch (IOException invalid) { return failed("WEB_INVALID_RESPONSE"); }
        catch (ExecutionException failure) {
            return failed(oversized.get() ? "WEB_RESPONSE_TOO_LARGE" : failure.getCause() instanceof HttpTimeoutException ? "WEB_TIMEOUT" : "WEB_NETWORK_UNKNOWN");
        }
        finally { permits.release(); }
    }
    private CopilotWebFetchDTO failed(String code) { return new CopilotWebFetchDTO(code, null); }
    @Override @PreDestroy public void close() { client.shutdownNow(); }
}
