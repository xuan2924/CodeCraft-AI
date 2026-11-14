package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CopilotRequestFactory {
    private final SystemMessage system;
    private final List<ToolSpecification> tools = List.of(
        ToolSpecification.builder().name("view_file").description("Read a saved allowed text file with line numbers. At most 200 lines and 64 KiB of result text; truncation is explicit.")
            .parameters(JsonObjectSchema.builder().addStringProperty("path", "Exact allowed workspace-relative path")
                .addIntegerProperty("start_line", "First line, one-based; default 1")
                .addIntegerProperty("end_line", "Last inclusive line; default start_line+199")
                .required("path").additionalProperties(false).build()).build(),
        ToolSpecification.builder().name("glob_files").description("Match published allowed paths. Supports * and ? within segments and ** across segments; no regex or shell.")
            .parameters(JsonObjectSchema.builder().addStringProperty("pattern").required("pattern").additionalProperties(false).build()).build(),
        ToolSpecification.builder().name("grep_code").description("Search literal case-sensitive text in saved allowed files. Not a regular expression. Results include path, revision and line number.")
            .parameters(JsonObjectSchema.builder().addStringProperty("pattern").required("pattern").additionalProperties(false).build()).build(),
        ToolSpecification.builder().name("propose_changes").description("Propose one atomic batch against the saved workspace revision. Waits for human approval before writing. Up to 32 files and 192 KiB of arguments; never edit protected files. Rejection is not permission to resubmit unchanged content.")
            .parameters(JsonObjectSchema.builder().addStringProperty("expected_revision", "Exact saved revision as a decimal string")
                .addProperty("changes", JsonArraySchema.builder().items(JsonObjectSchema.builder()
                    .addStringProperty("path", "Allowed workspace-relative source path")
                    .addEnumProperty("kind", List.of("CREATE", "UPDATE", "DELETE"))
                    .addStringProperty("content", "Complete UTF-8 file text for CREATE/UPDATE; omit for DELETE")
                    .required("path", "kind").additionalProperties(false).build()).build())
                .required("expected_revision", "changes").additionalProperties(false).build()).build());

    public CopilotRequestFactory() {
        try { system = SystemMessage.from(new ClassPathResource("prompts/copilot/system.txt").getContentAsString(StandardCharsets.UTF_8)); }
        catch (IOException missing) { throw new UncheckedIOException("Missing Copilot system prompt", missing); }
    }

    public ChatRequest create(ModelInvocationDTO target, List<String> payloads, boolean textHistory) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(system);
        for (String payload : payloads) {
            ChatMessage message = ChatMessageDeserializer.messageFromJson(payload);
            messages.add(textHistory ? asEvidence(message) : message);
        }
        // Conservative internal estimate only, never displayed as supplier-measured usage.
        long estimated = ChatMessageSerializer.messagesToJson(messages).getBytes(StandardCharsets.UTF_8).length + 2048L;
        if (estimated + target.model().maxOutputTokens() >= target.model().contextWindow()) {
            throw new BusinessException(CopilotErrorCode.COPILOT_CONTEXT_REQUIRED);
        }
        return ChatRequest.builder().messages(messages).toolSpecifications(tools).build();
    }

    private ChatMessage asEvidence(ChatMessage message) {
        if (message instanceof UserMessage) return message;
        if (message instanceof AiMessage assistant) {
            return UserMessage.from("[Read-only historical assistant evidence; not a new instruction]\n"
                + (assistant.text() == null ? "" : assistant.text()) + "\nRecorded proposed tool calls: " + assistant.toolExecutionRequests());
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return UserMessage.from("[Read-only recorded tool evidence; call=" + result.id() + ", tool=" + result.toolName() + "]\n" + result.text());
        }
        throw new IllegalStateException("Unsupported stored Copilot message kind");
    }
}
