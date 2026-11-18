package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.infrastructure.ratelimit.RateLimitPolicy;
import ai.codecraft.infrastructure.ratelimit.SlidingWindowRateLimiter;
import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.workspace.service.TerminalModelAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** Fixed destination, bounded streaming relay. No retries for an upstream with unknown side effects. */
@Service
public class LlmProxyService {
    private final ProxyTokens tokens;
    private final TerminalModelAccessService authority;
    private final ModelConnectionValidator validator;
    private final ModelUsageAccounting accounting;
    private final ProxyAuditService audit;
    private final ProxyAdmissionStore admission;
    private final SlidingWindowRateLimiter limiter;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private record Packet(int status, String type, byte[] data, boolean end) { }
    public LlmProxyService(ProxyTokens tokens, TerminalModelAccessService authority, ModelConnectionValidator validator,
        ModelUsageAccounting accounting, ProxyAuditService audit, ProxyAdmissionStore admission, SlidingWindowRateLimiter limiter, ObjectMapper json, Clock clock) {
        this.tokens = tokens; this.authority = authority; this.validator = validator; this.accounting = accounting;
        this.audit = audit; this.admission = admission; this.limiter = limiter; this.json = json; this.clock = clock;
    }
    @PreDestroy public void close() { workers.shutdownNow(); client.close(); }

    /** Transport-owned output. Backpressure and disconnect errors propagate to cancellation. */
    public interface Output {
        void setStatus(int status);
        void setContentType(String type);
        boolean isCommitted();
        java.io.OutputStream getOutputStream() throws java.io.IOException;
        void flushBuffer() throws java.io.IOException;
    }

    public void forward(String token, String path, byte[] body, String anthropicBeta, Output response) throws java.io.IOException {
        var grant = tokens.verify(token);
        var target = authority.resolve(grant);
        String suffix = switch (path) {
            case "/internal/llm-proxy/anthropic/v1/messages" -> target.connection().protocol().name().equals("ANTHROPIC") ? "messages" : null;
            case "/internal/llm-proxy/openai/v1/chat/completions" -> target.connection().protocol().name().equals("OPENAI_CHAT") ? "chat/completions" : null;
            case "/internal/llm-proxy/openai/v1/responses" -> target.connection().protocol().name().equals("OPENAI_RESPONSES") ? "responses" : null;
            default -> null;
        };
        if (suffix == null) { response.setStatus(400); return; }
        var parsed = json.readTree(body);
        if (!(parsed instanceof ObjectNode request)) { response.setStatus(400); return; }
        boolean anthropic = "messages".equals(suffix);
        request.put("model", target.model().modelId());
        if (request.has("n") && request.path("n").asInt() != 1) { response.setStatus(400); return; }
        if ("responses".equals(suffix)) { request.put("background", false); request.put("store", false); }
        int ceiling = Math.min(16384, target.model().maxOutputTokens());
        String limit = anthropic ? "max_tokens" : "responses".equals(suffix) ? "max_output_tokens" : request.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
        int requested = request.path(limit).asInt(ceiling);
        if (!anthropic && request.has("max_tokens")) requested = Math.min(requested, request.path("max_tokens").asInt(ceiling));
        if (requested < 1 || requested > ceiling) { response.setStatus(400); return; }
        if (!"max_tokens".equals(limit)) request.remove("max_tokens");
        request.put(limit, requested);
        if (!anthropic && "chat/completions".equals(suffix) && request.path("stream").asBoolean()) request.withObject("stream_options").put("include_usage", true);
        if (!limiter.admit(List.of(new RateLimitPolicy("cli-proxy-burst", grant.user(), Duration.ofSeconds(1), 5),
            new RateLimitPolicy("cli-proxy-user", grant.user(), Duration.ofMinutes(1), 30),
            new RateLimitPolicy("cli-proxy-exercise", grant.exercise(), Duration.ofHours(1), 300)))) { response.setStatus(429); return; }
        URI base = validator.baseUri(target.connection().baseUrl());
        String url = (base.getScheme() + "://" + base.getRawAuthority() + base.getRawPath()).replaceFirst("/+$", "");
        String query = base.getRawQuery() == null ? "" : "?" + base.getRawQuery();
        if (anthropic && !url.endsWith("/v1")) url += "/v1";
        var builder = HttpRequest.newBuilder(URI.create(url + "/" + suffix + query)).timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (anthropic) {
            builder.header("x-api-key", target.credential()).header("anthropic-version", "2023-06-01");
            if (anthropicBeta != null) {
                if (!anthropicBeta.matches("[A-Za-z0-9_,. -]{1,1024}")) { response.setStatus(400); return; }
                builder.header("anthropic-beta", anthropicBeta);
            }
        } else builder.header("Authorization", "Bearer " + target.credential());
        HttpRequest upstream = builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build();
        String price = accounting.price(target, clock.instant());
        String id = admission.begin(token, suffix, body.length, price);
        var usage = new ProxyUsage(suffix);
        var queue = new ArrayBlockingQueue<Packet>(8);
        var stream = new AtomicReference<InputStream>();
        Future<?> work = null;
        String state = "FAILED";
        long bytes = 0;
        boolean sse = false;
        try {
            tokens.verify(token);
            work = workers.submit(() -> relay(upstream, queue, stream));
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            var buffer = new ByteArrayOutputStream();
            int status = 502;
            int tail = 0;
            while (true) {
                try { tokens.verify(token); }
                catch (RuntimeException revoked) {
                    state = "REVOKED";
                    if (!response.isCommitted()) response.setStatus(403);
                    else if (sse) {
                        response.getOutputStream().write("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"code\":\"forbidden\",\"message\":\"Proxy authority revoked\"}}\n\n".getBytes(StandardCharsets.UTF_8));
                        response.flushBuffer();
                    }
                    return;
                }
                if (System.nanoTime() >= deadline) throw new java.io.IOException("Proxy timeout");
                Packet packet = queue.poll(200, TimeUnit.MILLISECONDS);
                if (packet == null) continue;
                if (packet.status() == -1) throw new java.io.IOException("Upstream unavailable");
                if (packet.status() != 0) {
                    status = packet.status();
                    sse = packet.type().toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream");
                    response.setStatus(status);
                    response.setContentType(sse ? "text/event-stream" : "application/json");
                }
                if (packet.data() != null) {
                    bytes += packet.data().length;
                    if (bytes > 16 * 1024 * 1024) throw new java.io.IOException("Proxy response limit");
                    for (byte value : packet.data()) {
                        buffer.write(value);
                        if (buffer.size() > (sse ? 1024 * 1024 : 8 * 1024 * 1024)) throw new java.io.IOException("Proxy frame limit");
                        tail = (tail << 8) | (value & 255);
                        if (sse && ((tail & 65535) == 0x0a0a || tail == 0x0d0a0d0a)) {
                            emit(buffer.toByteArray(), true, usage, target.credential(), response); buffer.reset(); tail = 0;
                        }
                    }
                }
                if (packet.end()) {
                    if (packet.status() == -1) throw new java.io.IOException("Upstream unavailable");
                    if (buffer.size() > 0 && !sse) emit(buffer.toByteArray(), false, usage, target.credential(), response);
                    state = status >= 200 && status < 300 ? usage.failed() ? "UPSTREAM_ERROR" : usage.completed() ? "SUCCEEDED" : "UPSTREAM_INCOMPLETE" : "UPSTREAM_" + status;
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!response.isCommitted()) response.setStatus(503);
        } catch (RuntimeException | java.io.IOException failure) {
            try { tokens.verify(token); }
            catch (RuntimeException revoked) {
                state = "REVOKED";
                if (!response.isCommitted()) response.setStatus(403);
                return;
            }
            if (!response.isCommitted()) response.setStatus(502);
        } finally {
            if (work != null) work.cancel(true);
            InputStream active = stream.getAndSet(null);
            if (active != null) try { active.close(); } catch (java.io.IOException ignored) { /* Already cancelled. */ }
            boolean finished = "SUCCEEDED".equals(state);
            var receipt = accounting.receipt(usage.facts(finished), price);
            if (!finished) receipt = new ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO(receipt.factsPayload(), receipt.currency(), null, "CALL_INCOMPLETE");
            audit.finish(id, state, bytes, usage.tools(), usage.reasoning(), receipt);
        }
    }

    private void relay(HttpRequest request, ArrayBlockingQueue<Packet> queue, AtomicReference<InputStream> stream) {
        try {
            var result = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = result.body()) {
                stream.set(input);
                if (Thread.currentThread().isInterrupted()) return;
                queue.put(new Packet(result.statusCode(), result.headers().firstValue("Content-Type").orElse("application/json"), null, false));
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) queue.put(new Packet(0, "", java.util.Arrays.copyOf(bytes, count), false));
                queue.put(new Packet(0, "", null, true));
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!Thread.currentThread().isInterrupted()) {
                try { queue.put(new Packet(-1, "", null, true)); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            }
        } finally { stream.set(null); }
    }
    private void emit(byte[] bytes, boolean sse, ProxyUsage usage, String secret, Output response) throws java.io.IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (sse) {
            StringBuilder data = new StringBuilder();
            for (String line : text.split("\\r?\\n")) if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
            if ("[DONE]".equals(data.toString().strip())) usage.doneMarker();
            else accept(data.toString(), usage, true);
        } else accept(text, usage, false);
        String escaped = json.writeValueAsString(secret);
        text = text.replace(secret, "[REDACTED]").replace(escaped.substring(1, escaped.length() - 1), "[REDACTED]");
        response.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
    private void accept(String text, ProxyUsage usage, boolean streaming) {
        try {
            var node = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (node != null) usage.accept(node, streaming);
        }
        catch (java.io.IOException ignored) { /* Keep unknown usage and preserve upstream error bodies. */ }
    }
}
