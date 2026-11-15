package ai.codecraft.modules.copilot.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.copilot.config.CopilotWebProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CopilotWebGatewayTest {
    @Test void sendsRealConfiguredRequestsWithBearerAndNeverRetriesHttpOrMalformedFailures() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/search", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-service-key");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).contains("query");
            int call = calls.incrementAndGet();
            byte[] body = (call == 1 ? "{\"results\":[]}" : "not-json").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(call == 3 ? 429 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var gateway = gateway(server, "fixture-service-key", Duration.ofSeconds(2))) {
            assertThat(gateway.call("search", Map.of("query", "test"), Duration.ofSeconds(5)).code()).isEqualTo("OK");
            assertThat(gateway.call("search", Map.of("query", "test"), Duration.ofSeconds(5)).code()).isEqualTo("WEB_INVALID_RESPONSE");
            assertThat(gateway.call("search", Map.of("query", "test"), Duration.ofSeconds(5)).code()).isEqualTo("WEB_RATE_LIMITED");
            assertThat(calls.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }
    @Test void missingConfigurationDeadlineAndInterruptionDoNotSendRequests() throws Exception {
        var properties = new CopilotWebProperties("", URI.create("http://127.0.0.1:1/"), Duration.ofMillis(50));
        try (var gateway = new CopilotWebGateway(properties, new ObjectMapper())) {
            assertThat(gateway.call("search", Map.of(), Duration.ofSeconds(1)).code()).isEqualTo("WEB_NOT_CONFIGURED");
            Thread.currentThread().interrupt();
            try { assertThatThrownBy(() -> gateway.call("search", Map.of(), Duration.ofSeconds(1))).isInstanceOf(InterruptedException.class); }
            finally { Thread.interrupted(); }
        }
        assertThat(properties.toString()).isEqualTo("CopilotWebProperties[REDACTED]");
    }
    @Test void boundsTransportAndEntireResponseTime() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            // Headers and a partial body arrived; the total deadline must still cancel the incomplete body.
        });
        server.start();
        try (var gateway = gateway(server, "fixture", Duration.ofSeconds(2))) {
            assertThat(gateway.call("large", Map.of(), Duration.ofSeconds(5)).code()).isEqualTo("WEB_RESPONSE_TOO_LARGE");
            assertThat(gateway.call("slow", Map.of(), Duration.ofMillis(50)).code()).isEqualTo("WEB_TIMEOUT");
            assertThat(gateway.call("search", Map.of(), Duration.ZERO).code()).isEqualTo("WEB_DEADLINE_EXCEEDED");
        } finally { server.stop(0); }
    }
    @Test void interruptsAnInFlightBodyWithoutWaitingForItsTimeout() throws Exception {
        var arrived = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 100); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); arrived.countDown();
        });
        server.start();
        try (var gateway = gateway(server, "fixture", Duration.ofSeconds(5))) {
            var completed = new CompletableFuture<String>();
            var thread = Thread.ofPlatform().start(() -> {
                try { completed.complete(gateway.call("cancel", Map.of(), Duration.ofSeconds(5)).code()); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); completed.complete("CANCELLED"); }
            });
            try {
                assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue(); thread.interrupt();
                assertThat(completed.get(2, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
            } finally { thread.interrupt(); thread.join(2000); }
        } finally { server.stop(0); }
    }
    private CopilotWebGateway gateway(HttpServer server, String key, Duration timeout) {
        return new CopilotWebGateway(new CopilotWebProperties(key, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), timeout), new ObjectMapper());
    }
}
