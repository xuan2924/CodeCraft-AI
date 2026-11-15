package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.model.dto.CopilotEvidenceDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CopilotEvidenceToolsTest {
    private final CopilotEvidenceStore store = mock(CopilotEvidenceStore.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotEvidenceTools tools = new CopilotEvidenceTools(store, json);
    private final String id = UUID.randomUUID().toString();

    @Test void validatesParametersBeforeReadingAnyPrivateEvidence() throws Exception {
        for (String args : List.of("{}", "[]", "{\"id\":false}", "{\"id\":\"https://example.test\"}",
            "{\"id\":\"" + id + "\",\"offset\":\"1\"}", "{\"id\":\"" + id + "\",\"limit\":999999}",
            "{\"id\":\"" + id + "\",\"shell\":true}", "{\"id\":\"" + id + "\",\"id\":\"" + id + "\"}",
            "{\"id\":\"" + id + "\"} {}")) {
            assertThat(json.readTree(tools.execute("task", "worker", args)).path("code").asText()).isEqualTo("COPILOT_INVALID_REQUEST");
        }
        verify(store, never()).read(anyString(), anyString(), anyString());
    }

    @Test void pagesByUtf8BoundaryAndBoundsEncodedOutputEvenForEscapedCharacters() throws Exception {
        String original = "😀中文\u0001".repeat(1500);
        when(store.read("task", "worker", id)).thenReturn(new CopilotEvidenceDTO(id, "USER", 7,
            ChatMessageSerializer.messageToJson(UserMessage.from(original)), null, null));
        String encoded = tools.execute("task", "worker", "{\"id\":\"" + id + "\",\"limit\":4096}");
        var first = json.readTree(encoded);
        assertThat(first.path("code").asText()).isEqualTo("OK");
        String text = first.path("text").asText(); int next = first.path("nextOffset").asInt();
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isEqualTo(next);
        assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(text)).isTrue(); assertThat(original).startsWith(text);
        assertThat(encoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
        var second = json.readTree(tools.execute("task", "worker", "{\"id\":\"" + id + "\",\"offset\":" + next + "}"));
        assertThat(original).startsWith(text + second.path("text").asText());
        assertThat(json.readTree(tools.execute("task", "worker", "{\"id\":\"" + id + "\",\"offset\":1}")).path("code").asText()).isEqualTo("COPILOT_INVALID_REQUEST");
    }

    @Test void doesNotExposeProviderThinkingOrTreatSummaryAsBusinessTruth() throws Exception {
        when(store.read("task", "worker", id)).thenReturn(new CopilotEvidenceDTO(id, "ASSISTANT", 8,
            ChatMessageSerializer.messageToJson(AiMessage.builder().text("visible result").thinking("opaque-private-signature").build()), null, null));
        assertThat(tools.execute("task", "worker", "{\"id\":\"" + id + "\"}")).contains("visible result").doesNotContain("opaque-private-signature");
        when(store.read("task", "worker", id)).thenReturn(new CopilotEvidenceDTO(id, "SUMMARY", 8, "{\"goal\":\"historical\"}", "[]", null));
        assertThat(tools.execute("task", "worker", "{\"id\":\"" + id + "\"}")).contains("non-authoritative", "historical", "sourceMessageIds");
    }
}
