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
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real native SDKs; URL policy is substituted only to reach this bounded HTTP loopback fixture. */
class NativeProtocolRoundTripTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = ModelProtocol.class, names = {"OPENAI_RESPONSES", "ANTHROPIC"})
    void nativeOpaqueReasoningUsageAndFragmentedToolsSurviveAStatelessRoundTrip(ModelProtocol protocol) throws Exception {
        var received = new ArrayBlockingQueue<JsonNode>(3);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = new ThreadPoolExecutor(1, 2, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
        server.setExecutor(threads);
        var count = new AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                received.add(json.readTree(exchange.getRequestBody()));
                boolean tools = count.getAndIncrement() == 0;
                byte[] bytes = (protocol == ModelProtocol.ANTHROPIC ? anthropic(tools) : responses(tools)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        var validator = mock(ModelConnectionValidator.class);
        when(validator.baseUri(anyString())).thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        var properties = new ModelCallProperties(Duration.ofSeconds(5), Duration.ofSeconds(2), 2, 65536, 4096, 32768);
        var connection = new ModelConnectionResponse("connection", "Fixture", "https://fixture.invalid", protocol, ModelProviderPreset.CUSTOM, "1", "1", true, false);
        var model = new ModelConfigurationResponse("model", "connection", "Fixture", "fixture", 100000, 4096, ModelThinkingMode.ON, protocol == ModelProtocol.ANTHROPIC ? null : "low", protocol == ModelProtocol.ANTHROPIC ? 1024 : null, null, "1", "1", "USER_PROVIDED", false);
        var target = new ModelInvocationDTO(connection, model, "fixture-only");
        var limit = new ModelInvocationLimitsDTO(4096, protocol == ModelProtocol.ANTHROPIC ? 1024 : null);
        var tool = ToolSpecification.builder().name("inspect").description("Read a fixture")
            .parameters(JsonObjectSchema.builder().addStringProperty("path").required("path").build()).build();
        var history = new ArrayList<ChatMessage>(); history.add(UserMessage.from("Inspect two files"));
        try (var driver = new ModelCallDriver(new ModelSdkClientFactory(validator, properties), properties, Clock.systemUTC())) {
            var first = driver.call(target, ChatRequest.builder().messages(history).toolSpecifications(tool).build(), limit, 32768);
            var original = first.response().aiMessage();
            var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(ChatMessageSerializer.messageToJson(original));
            assertThat(assistant).isEqualTo(original);
            assertThat(assistant.thinking()).isEqualTo("plan carefully");
            assertThat(assistant.toolExecutionRequests()).hasSize(2);
            assertThat(assistant.toolExecutionRequests().getFirst().id()).isEqualTo("call-a");
            assertThat(assistant.toolExecutionRequests().getFirst().arguments()).isEqualTo("{\"path\":\"first.txt\"}");
            assertThat(assistant.toolExecutionRequests().getLast().id()).isEqualTo("call-b");
            assertThat(first.response().tokenUsage().inputTokenCount()).isEqualTo(10);
            assertThat(first.response().tokenUsage().outputTokenCount()).isEqualTo(8);
            if (protocol == ModelProtocol.ANTHROPIC) {
                assertThat(assistant.attributes()).containsEntry("thinking_signature", "signature-fixture")
                    .containsEntry("redacted_thinking", List.of("redacted-fixture"));
                var usage = (AnthropicTokenUsage) first.response().tokenUsage();
                assertThat(usage.cacheCreationInputTokens()).isEqualTo(4); assertThat(usage.cacheReadInputTokens()).isEqualTo(3);
            } else {
                assertThat(assistant.attributes()).containsEntry("encrypted_reasoning", "encrypted-fixture");
                var usage = (OpenAiTokenUsage) first.response().tokenUsage();
                assertThat(usage.inputTokensDetails().cachedTokens()).isEqualTo(3);
                assertThat(usage.outputTokensDetails().reasoningTokens()).isEqualTo(5);
            }
            history.add(assistant);
            for (var call : assistant.toolExecutionRequests()) history.add(ToolExecutionResultMessage.from(call, "confirmed fixture"));
            var second = driver.call(target, ChatRequest.builder().messages(history).toolSpecifications(tool).build(), limit, 32768);
            assertThat(second.response().aiMessage().text()).isEqualTo("Confirmed");
            var initial = received.poll(1, TimeUnit.SECONDS); var continuation = received.poll(1, TimeUnit.SECONDS);
            if (protocol == ModelProtocol.ANTHROPIC) {
                assertThat(initial.path("thinking").path("type").asText()).isEqualTo("enabled");
                var messages = continuation.path("messages");
                assertThat(messages.get(1).path("content").toString()).contains("signature-fixture", "redacted-fixture", "call-a", "call-b");
                assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
                assertThat(messages.get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("call-a");
                assertThat(messages.get(2).path("content").get(1).path("tool_use_id").asText()).isEqualTo("call-b");
            } else {
                assertThat(initial.path("store").asBoolean(true)).isFalse();
                assertThat(initial.path("include").toString()).contains("reasoning.encrypted_content");
                assertThat(continuation.has("previous_response_id")).isFalse();
                var input = continuation.path("input").toString();
                assertThat(input).contains("encrypted-fixture", "function_call_output", "call-a", "call-b");
            }
            assertThat(count.get()).isEqualTo(2);
        } finally { server.stop(0); threads.shutdownNow(); }
    }

    private String responses(boolean tools) throws IOException {
        var output = new ArrayList<Map<String, Object>>();
        var body = new StringBuilder();
        if (tools) {
            output.add(Map.of("type", "reasoning", "id", "reason-1", "encrypted_content", "encrypted-fixture", "summary", List.of(Map.of("type", "summary_text", "text", "plan carefully"))));
            body.append(event("response.reasoning_summary_text.delta", Map.of("delta", "plan ")));
            body.append(event("response.reasoning_summary_text.delta", Map.of("delta", "carefully")));
            for (int index = 0; index < 2; index++) {
                String id = index == 0 ? "call-a" : "call-b";
                String path = index == 0 ? "first.txt" : "second.txt";
                String arguments = "{\"path\":\"" + path + "\"}";
                var item = Map.<String, Object>of("type", "function_call", "id", "item-" + id, "call_id", id, "name", "inspect", "arguments", arguments, "status", "completed");
                output.add(item);
                body.append(event("response.output_item.added", Map.of("output_index", index + 1, "item", Map.of("type", "function_call", "id", "item-" + id, "call_id", id, "name", "inspect", "arguments", ""))));
                body.append(event("response.function_call_arguments.delta", Map.of("output_index", index + 1, "item_id", "item-" + id, "delta", "{\"path\":")));
                body.append(event("response.function_call_arguments.delta", Map.of("output_index", index + 1, "item_id", "item-" + id, "delta", "\"" + path + "\"}")));
                body.append(event("response.output_item.done", Map.of("output_index", index + 1, "item", item)));
            }
        } else {
            output.add(Map.of("type", "message", "id", "message-1", "role", "assistant", "content", List.of(Map.of("type", "output_text", "text", "Confirmed"))));
            body.append(event("response.output_text.delta", Map.of("delta", "Confirmed")));
        }
        return body + event("response.completed", Map.of("response", Map.of("id", "response-fixture", "model", "fixture", "status", "completed", "output", output,
            "usage", Map.of("input_tokens", 10, "output_tokens", 8, "total_tokens", 18, "input_tokens_details", Map.of("cached_tokens", 3), "output_tokens_details", Map.of("reasoning_tokens", 5)))));
    }

    private String anthropic(boolean tools) throws IOException {
        var body = new StringBuilder(event("message_start", Map.of("message", Map.of("id", "message-fixture", "type", "message", "role", "assistant", "model", "fixture", "content", List.of(),
            "usage", Map.of("input_tokens", 10, "output_tokens", 1, "cache_creation_input_tokens", 4, "cache_read_input_tokens", 3)))));
        if (tools) {
            body.append(blockStart(0, Map.of("type", "thinking", "thinking", "", "signature", "")));
            body.append(delta(0, Map.of("type", "thinking_delta", "thinking", "plan ")));
            body.append(delta(0, Map.of("type", "thinking_delta", "thinking", "carefully")));
            body.append(delta(0, Map.of("type", "signature_delta", "signature", "signature-fixture")));
            body.append(blockStop(0));
            body.append(blockStart(1, Map.of("type", "redacted_thinking", "data", "redacted-fixture"))).append(blockStop(1));
            for (int index = 0; index < 2; index++) {
                body.append(blockStart(index + 2, Map.of("type", "tool_use", "id", index == 0 ? "call-a" : "call-b", "name", "inspect", "input", Map.of())));
                body.append(delta(index + 2, Map.of("type", "input_json_delta", "partial_json", "{\"path\":")));
                body.append(delta(index + 2, Map.of("type", "input_json_delta", "partial_json", "\"" + (index == 0 ? "first.txt" : "second.txt") + "\"}")));
                body.append(blockStop(index + 2));
            }
        } else {
            body.append(blockStart(0, Map.of("type", "text", "text", "")));
            body.append(delta(0, Map.of("type", "text_delta", "text", "Confirmed"))).append(blockStop(0));
        }
        body.append(event("message_delta", Map.of("delta", Map.of("stop_reason", tools ? "tool_use" : "end_turn"), "usage", Map.of("output_tokens", 8))));
        return body + event("message_stop", Map.of());
    }

    private String blockStart(int index, Map<String, Object> block) throws IOException { return event("content_block_start", Map.of("index", index, "content_block", block)); }
    private String blockStop(int index) throws IOException { return event("content_block_stop", Map.of("index", index)); }
    private String delta(int index, Map<String, Object> delta) throws IOException { return event("content_block_delta", Map.of("index", index, "delta", delta)); }
    private String event(String type, Map<String, Object> fields) throws IOException {
        var value = new LinkedHashMap<String, Object>(fields); value.put("type", type);
        return "event: " + type + "\ndata: " + json.writeValueAsString(value) + "\n\n";
    }
}
