package ai.codecraft.modules.modelaccess.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real SDK + driver against a loopback protocol fixture. Only the test URL-policy collaborator
 * redirects a declared HTTPS fixture target to loopback HTTP; production validation is unchanged. */
class DeepSeekThinkingProtocolTest {
    @Test
    void streamingThinkingAndTwoToolCallsSurvivePersistenceAndEverySubsequentRoundTrip() throws Exception {
        var json = new ObjectMapper();
        var received = new ArrayBlockingQueue<JsonNode>(4);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = new ThreadPoolExecutor(1, 2, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
        server.setExecutor(executor);
        var counter = new AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                received.add(json.readTree(exchange.getRequestBody()));
                String response = counter.getAndIncrement() == 0 ? toolsResponse() : finalResponse();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        var validator = mock(ModelConnectionValidator.class);
        when(validator.baseUri(anyString())).thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        var properties = new ModelCallProperties(Duration.ofSeconds(5), Duration.ofSeconds(2), 3, 65536, 4096, 32768);
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://fixture.invalid", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "deepseek-flash", 100000, 4096, ModelThinkingMode.ON, "low", null, null, "1", "1", "USER_PROVIDED", false);
        var target = new ModelInvocationDTO(connection, model, "fixture-only-not-a-secret");
        var limit = new ModelInvocationLimitsDTO(4096, null);
        var tool = ToolSpecification.builder().name("inspect").description("Read fixture")
            .parameters(JsonObjectSchema.builder().addStringProperty("path").required("path").build()).build();
        var history = new ArrayList<ChatMessage>(); history.add(UserMessage.from("Inspect both files"));
        try (var driver = new ModelCallDriver(new ModelSdkClientFactory(validator, properties), properties, Clock.systemUTC())) {
            var visible = new StringBuilder();
            var first = driver.call(target, ChatRequest.builder().messages(history).toolSpecifications(tool).build(), limit, 32768,
                Clock.systemUTC().instant().plusSeconds(5), visible::append);
            var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(ChatMessageSerializer.messageToJson(first.response().aiMessage()));
            assertThat(assistant.thinking()).isEqualTo("plan carefully");
            assertThat(visible.toString()).isEqualTo("Reading");
            assertThat(assistant.toolExecutionRequests()).hasSize(2);
            assertThat(assistant.toolExecutionRequests().getFirst().arguments()).isEqualTo("{\"path\":\"first.txt\"}");
            assertThat(assistant.toolExecutionRequests().getLast().id()).isEqualTo("call-b");
            var usage = (OpenAiTokenUsage) first.response().tokenUsage();
            assertThat(usage.inputTokenCount()).isEqualTo(12); assertThat(usage.outputTokenCount()).isEqualTo(8);
            assertThat(usage.inputTokensDetails().cachedTokens()).isEqualTo(3);
            assertThat(usage.outputTokensDetails().reasoningTokens()).isEqualTo(5);
            assertThat(usage.totalTokenCount()).isEqualTo(20);
            history.add(assistant);
            for (var call : assistant.toolExecutionRequests()) history.add(ToolExecutionResultMessage.from(call, "confirmed fixture"));
            var second = driver.call(target, ChatRequest.builder().messages(history).toolSpecifications(tool).build(), limit, 32768);
            assertThat(second.response().aiMessage().thinking()).isEqualTo("done thinking");
            history.add(ChatMessageDeserializer.messageFromJson(ChatMessageSerializer.messageToJson(second.response().aiMessage())));
            history.add(UserMessage.from("A new user turn"));
            driver.call(target, ChatRequest.builder().messages(history).toolSpecifications(tool).build(), limit, 32768);
            var firstRequest = received.poll(1, TimeUnit.SECONDS); var secondRequest = received.poll(1, TimeUnit.SECONDS); var thirdRequest = received.poll(1, TimeUnit.SECONDS);
            assertThat(firstRequest.path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(firstRequest.path("reasoning_effort").asText()).isEqualTo("low");
            assertThat(secondRequest.path("messages").get(1).path("reasoning_content").asText()).isEqualTo("plan carefully");
            assertThat(secondRequest.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("call-a");
            assertThat(secondRequest.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("call-b");
            assertThat(thirdRequest.path("messages").get(4).path("reasoning_content").asText()).isEqualTo("done thinking");
            assertThat(counter.get()).isEqualTo(3);
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private String toolsResponse() {
        return delta("{\"role\":\"assistant\",\"reasoning_content\":\"plan \"}")
            + delta("{\"reasoning_content\":\"carefully\",\"content\":\"Reading\"}")
            + delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call-a\",\"type\":\"function\",\"function\":{\"name\":\"inspect\",\"arguments\":\"{\\\"path\\\":\"}}]}")
            + delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"first.txt\\\"}\"}},{\"index\":1,\"id\":\"call-b\",\"type\":\"function\",\"function\":{\"name\":\"inspect\",\"arguments\":\"{\\\"path\\\":\\\"second.txt\\\"}\"}}]}")
            + finish("tool_calls");
    }
    private String finalResponse() { return delta("{\"role\":\"assistant\",\"reasoning_content\":\"done thinking\"}") + delta("{\"content\":\"Confirmed\"}") + finish("stop"); }
    private String delta(String delta) { return "data: {\"id\":\"fixture\",\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"; }
    private String finish(String reason) {
        return "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}\n\n"
            + "data: {\"id\":\"fixture\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":3},\"completion_tokens_details\":{\"reasoning_tokens\":5}}}\n\ndata: [DONE]\n\n";
    }
}
