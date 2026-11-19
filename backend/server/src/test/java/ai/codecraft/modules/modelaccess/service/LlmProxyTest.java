package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.ratelimit.SlidingWindowRateLimiter;
import ai.codecraft.modules.modelaccess.controller.LlmProxyController;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.modelaccess.repository.ProxyAuditRepository;
import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.modelaccess.config.ProxyGrantProperties;
import ai.codecraft.modules.workspace.service.TerminalModelAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class LlmProxyTest {
    private static final String ID = "10000000-0000-0000-0000-000000000001";
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final TerminalModelAccessService authority = mock(TerminalModelAccessService.class);
    protected ProxyAuditRepository repository;
    protected ProxyAuditRepository createRepository() { return mock(ProxyAuditRepository.class); }
    private final SlidingWindowRateLimiter limiter = mock(SlidingWindowRateLimiter.class);
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> payload = new AtomicReference<>();
    private HttpServer upstream;
    private LlmProxyService service;
    private ProxyTokens tokens;
    private String token;
    private MockMvc mvc;
    private final ModelConnectionResponse connection = mock(ModelConnectionResponse.class);

    @BeforeEach void setup() throws Exception {
        repository = createRepository();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.start();
        var grant = new TerminalModelAccessService.Authority(ID, ID, ID, 7);
        when(authority.authorize(ID, ID, 7)).thenAnswer(call -> { if (revoked.get()) throw new IllegalStateException("revoked"); return grant; });
        tokens = new ProxyTokens(clock, authority, new ProxyGrantProperties(java.time.Duration.ofHours(1))); token = tokens.issue(ID, ID, 7);
        var model = mock(ModelConfigurationResponse.class);
        when(model.maxOutputTokens()).thenReturn(4096); when(model.modelId()).thenReturn("selected-model");
        when(connection.protocol()).thenReturn(ModelProtocol.OPENAI_CHAT);
        when(connection.preset()).thenReturn(ModelProviderPreset.CUSTOM);
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        when(connection.baseUrl()).thenReturn(base);
        when(authority.resolve(any())).thenReturn(new ModelInvocationDTO(connection, model, "real-secret-key"));
        var validator = mock(ModelConnectionValidator.class);
        when(validator.baseUri(base)).thenReturn(URI.create(base)); // HTTP permitted only by this test double.
        when(limiter.admit(any())).thenReturn(true);
        var accounting = new ModelUsageAccounting(new ModelUsageNormalizer(), new ModelPriceCatalog(), new ModelCostCalculator());
        var audit = new ProxyAuditService(repository, clock);
        service = new LlmProxyService(tokens, authority, validator, accounting, audit, new ProxyAdmissionStore(tokens, audit, mock(ai.codecraft.modules.workspace.service.StorageCapacityStore.class)), limiter, json, clock);
        mvc = MockMvcBuilders.standaloneSetup(new LlmProxyController(tokens, service, json)).build();
    }
    @AfterEach void cleanup() { upstream.stop(0); service.close(); }
    @ParameterizedTest @CsvSource({"ANTHROPIC,false", "ANTHROPIC,true", "OPENAI_CHAT,false", "OPENAI_CHAT,true", "OPENAI_RESPONSES,false", "OPENAI_RESPONSES,true"})
    void protocolsReplaceKeysStreamAndAccount(ModelProtocol protocol, boolean stream) throws Exception {
        when(connection.protocol()).thenReturn(protocol);
        boolean anthropic = protocol == ModelProtocol.ANTHROPIC;
        String data = anthropic
            ? "{\"type\":\"message\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"cache_read_input_tokens\":2,\"cache_creation_input_tokens\":3}}"
            : "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{}]}}],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":2},\"completion_tokens_details\":{\"reasoning_tokens\":1}}}";
        if (protocol == ModelProtocol.OPENAI_RESPONSES) data = data.replace("\"choices\":[", "\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}],\"ignored\":[");
        String marker = anthropic ? "{\"type\":\"message_stop\"}" : protocol == ModelProtocol.OPENAI_RESPONSES ? "{\"type\":\"response.completed\"}" : "[DONE]";
        String body = stream ? "data: " + data + "\n\ndata: " + marker + "\n\n" : data;
        upstream.createContext("/", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst(anthropic ? "x-api-key" : "Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.getResponseHeaders().set("X-Leaked-Key", "real-secret-key");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        var result = call(path(protocol), "{\"model\":\"untrusted-model\",\"stream\":" + stream + "}");
        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getContentAsString()).isEqualTo(body);
        assertThat(result.getHeader("X-Leaked-Key")).isNull();
        assertThat(key.get()).isEqualTo(anthropic ? "real-secret-key" : "Bearer real-secret-key");
        assertThat(payload.get()).contains("selected-model").doesNotContain(token, "real-secret-key", "untrusted-model");
        var receipt = ArgumentCaptor.forClass(ModelUsageReceiptDTO.class);
        verify(repository).start(anyString(), eq(ID), eq(clock.instant()), anyString(), anyInt(), anyString());
        verify(repository).finish(anyString(), eq(clock.instant()), eq("SUCCEEDED"), anyLong(), eq(1), any(), receipt.capture());
        assertThat(receipt.getValue().factsPayload()).contains("\"inputTokens\":15", "\"outputTokens\":4", "\"cacheReadTokens\":2");
        assertThat(receipt.getValue().amount()).isNull();
    }
    @Test void eofAfterUsageDoesNotCompleteOrPriceTheCall() throws Exception {
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"usage\":{\"completion_tokens\":4}}\n\n".getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        call(path(ModelProtocol.OPENAI_CHAT), "{\"stream\":true}");
        var receipt = ArgumentCaptor.forClass(ModelUsageReceiptDTO.class);
        verify(repository).finish(anyString(), any(), eq("UPSTREAM_INCOMPLETE"), anyLong(), eq(0), any(), receipt.capture());
        assertThat(receipt.getValue().priceReason()).isEqualTo("CALL_INCOMPLETE");
        assertThat(receipt.getValue().amount()).isNull();
    }
    @Test void upstreamErrorsAreNotRetriedAndSecretsAreRedacted() throws Exception {
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(429, 0);
            exchange.getResponseBody().write("{\"error\":\"real-secret-key\"}".getBytes(StandardCharsets.UTF_8)); exchange.close();
        });
        var result = call(path(ModelProtocol.OPENAI_CHAT), "{}");
        assertThat(result.getStatus()).isEqualTo(429);
        assertThat(result.getContentAsString()).contains("[REDACTED]").doesNotContain("real-secret-key");
        verify(repository).finish(anyString(), any(), eq("UPSTREAM_429"), anyLong(), eq(0), any(), any());
    }
    @Test void silentStreamIsActivelyRevokedAndCancelled() throws Exception {
        var started = new CountDownLatch(1); var committed = new CountDownLatch(1); var release = new CountDownLatch(1);
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write("data: {}\n\n".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
            started.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = new org.springframework.mock.web.MockHttpServletResponse();
            var future = executor.submit(() -> {
                service.forward(token, path(ModelProtocol.OPENAI_CHAT), "{\"stream\":true}".getBytes(StandardCharsets.UTF_8), null, new LlmProxyService.Output() {
                    @Override public void setStatus(int status) { result.setStatus(status); }
                    @Override public void setContentType(String type) { result.setContentType(type); }
                    @Override public boolean isCommitted() { return result.isCommitted(); }
                    @Override public java.io.OutputStream getOutputStream() { return result.getOutputStream(); }
                    @Override public void flushBuffer() { result.flushBuffer(); committed.countDown(); }
                });
                return null;
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(committed.await(2, TimeUnit.SECONDS)).isTrue();
            revoked.set(true);
            future.get(2, TimeUnit.SECONDS);
            assertThat(result.getStatus()).isEqualTo(200);
            assertThat(result.getContentAsString()).contains("forbidden");
            assertThat(call(path(ModelProtocol.OPENAI_CHAT), "{}").getStatus()).isEqualTo(403);
        } finally { release.countDown(); }
    }
    @Test void proxyGrantExpiresExactlyAtConfiguredDeadline() {
        Clock adjustable = mock(Clock.class);
        var issued = clock.instant();
        when(adjustable.instant()).thenReturn(issued, issued.plusSeconds(2699), issued.plusSeconds(2700));
        var grants = new ProxyTokens(adjustable, authority, new ProxyGrantProperties(java.time.Duration.ofMinutes(45)));
        var grant = grants.issue(ID, ID, 7);
        assertThat(grants.verify(grant).task()).isEqualTo(ID);
        assertThatThrownBy(() -> grants.verify(grant)).isInstanceOf(IllegalStateException.class);
    }

    @Test void tamperExpiryRateAndProtocolFailClosed() throws Exception {
        assertThatThrownBy(() -> tokens.verify(token + "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProxyTokens(clock, authority, new ProxyGrantProperties(java.time.Duration.ofHours(1))).verify(token)).isInstanceOf(IllegalStateException.class);
        assertThat(call(path(ModelProtocol.ANTHROPIC), "{}").getStatus()).isEqualTo(400);
        when(limiter.admit(any())).thenReturn(false);
        assertThat(call(path(ModelProtocol.OPENAI_CHAT), "{}").getStatus()).isEqualTo(429);
        revoked.set(true);
        assertThat(call(path(ModelProtocol.OPENAI_CHAT), "{}").getStatus()).isEqualTo(403);
    }
    private org.springframework.mock.web.MockHttpServletResponse call(String path, String body) throws Exception {
        return mvc.perform(post(path).servletPath(path).requestAttr("codecraft.proxy.token", token).contentType("application/json").content(body)).andReturn().getResponse();
    }
    private static String path(ModelProtocol protocol) {
        return "/internal/llm-proxy/" + switch (protocol) {
            case ANTHROPIC -> "anthropic/v1/messages";
            case OPENAI_CHAT -> "openai/v1/chat/completions";
            case OPENAI_RESPONSES -> "openai/v1/responses";
        };
    }
}
