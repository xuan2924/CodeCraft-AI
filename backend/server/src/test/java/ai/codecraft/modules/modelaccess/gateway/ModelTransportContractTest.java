package ai.codecraft.modules.modelaccess.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** HTTP is a loopback transport fixture, never a product URL-policy exception. No paid models. */
class ModelTransportContractTest {
    private HttpServer server;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    @AfterEach void cleanup() { if (server != null) server.stop(0); executor.shutdownNow(); }

    @ParameterizedTest
    @ValueSource(ints = {200, 401})
    void successAndErrorBodiesAreBoundedBeforeSdkParsing(int status) throws Exception {
        serve(status, "x".repeat(256));
        try (var builder = new BoundedModelHttpClientBuilder(64, 32)) {
            var client = builder.build();
            var failure = assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(url()).build(), HttpResponse.BodyHandlers.ofString()));
            assertTrue(hasLimitCause(failure));
        }
    }

    @Test
    void redirectNeverForwardsAuthorizationToAnotherRequest() throws Exception {
        var target = new AtomicInteger();
        open();
        server.createContext("/target", exchange -> { target.incrementAndGet(); exchange.close(); });
        server.createContext("/", exchange -> { exchange.getResponseHeaders().set("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.start();
        try (var builder = new BoundedModelHttpClientBuilder(64, 32)) {
            var response = builder.build().send(HttpRequest.newBuilder(url()).header("Authorization", "Bearer fixture-only").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, response.statusCode()); assertEquals(0, target.get());
            assertThrows(IllegalArgumentException.class, () -> builder.followRedirects(HttpClient.Redirect.ALWAYS));
        }
    }

    @Test
    void nativeSdkCannotBufferAnUnterminatedOversizedSseLine() throws Exception {
        serve(200, "data: " + "x".repeat(256));
        try (var builder = new BoundedModelHttpClientBuilder(64, 32)) {
            var model = OpenAiStreamingChatModel.builder().baseUrl(url().toString()).apiKey("fixture-not-a-real-key").modelName("fixture")
                .httpClientBuilder(new JdkHttpClientBuilder().httpClientBuilder(builder)).logRequests(false).logResponses(false).build();
            var error = new CompletableFuture<Throwable>();
            model.chat(ChatRequest.builder().messages(UserMessage.from("fixture")).build()).subscribe(new Flow.Subscriber<ChatModelStreamingEvent>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ChatModelStreamingEvent event) { }
                @Override public void onError(Throwable failure) { error.complete(failure); }
                @Override public void onComplete() { error.complete(null); }
            });
            assertTrue(hasLimitCause(error.get(3, TimeUnit.SECONDS)));
        }
    }

    @Test
    void nativeJdkFutureCanBeCancelledBeforeHeadersAndFirstToken() throws Exception {
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        open();
        server.createContext("/", exchange -> {
            arrived.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var builder = new BoundedModelHttpClientBuilder(64, 32)) {
            var result = builder.build().sendAsync(HttpRequest.newBuilder(url()).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            result.cancel(true);
            assertTrue(result.isCompletedExceptionally());
            assertThrows(RuntimeException.class, result::join);
        } finally { release.countDown(); }
    }

    @Test
    void closedTransportCannotCreateALateUnownedClient() {
        var builder = new BoundedModelHttpClientBuilder(64, 32);
        builder.build(); builder.close(); builder.close();
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void sdkMessageCodecPreservesThinkingOpaqueAttributesAndToolPairing() {
        var tool = ToolExecutionRequest.builder().id("call-fixture").name("view_file").arguments("{\"path\":\"Main.java\"}").build();
        var assistant = AiMessage.builder().text("fixture").thinking("reasoning fixture").attributes(Map.of("thinking_signature", "signature", "encrypted_reasoning", "opaque"))
            .toolExecutionRequests(List.of(tool)).build();
        var restored = ChatMessageDeserializer.messagesFromJson(ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("goal"), assistant, ToolExecutionResultMessage.from(tool, "result"))));
        var replay = (AiMessage) restored.get(1);
        assertEquals(assistant.thinking(), replay.thinking()); assertEquals(assistant.attributes(), replay.attributes());
        assertEquals("call-fixture", replay.toolExecutionRequests().getFirst().id());
        assertNotNull(restored.get(2));
    }

    private void open() throws IOException { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor); }
    private void serve(int status, String body) throws IOException {
        open(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        }); server.start();
    }
    private URI url() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
    private boolean hasLimitCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof ModelResponseLimitException) return true;
        return false;
    }
}
