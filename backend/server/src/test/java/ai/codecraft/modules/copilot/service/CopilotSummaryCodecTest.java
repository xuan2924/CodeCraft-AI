package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CopilotSummaryCodecTest {
    private final CopilotSummaryCodec codec = new CopilotSummaryCodec(new ObjectMapper());
    private final String id = UUID.randomUUID().toString();
    private String valid() { return "{\"goal\":\"keep user decisions\",\"decisions\":[\"rejected old patch\"],\"changes\":[],\"state\":\"tests failed\",\"evidence\":[\"" + id + "\"]}"; }

    @Test void acceptsBoundedJsonOrOneOuterFenceAndPreservesDecisionsAndSourceReferences() {
        assertThat(codec.prepare(AiMessage.from(valid()), 2048, List.of(id), null)).contains("rejected old patch", "tests failed", id);
        assertThat(codec.prepare(AiMessage.from("```json\n" + valid() + "\n```"), 2048, List.of(id), null))
            .isEqualTo(codec.prepare(AiMessage.from(valid()), 2048, List.of(id), null));
    }
    @Test void rejectsMissingUnknownDuplicateAndTrailingFieldsRatherThanPublishingAPartialSummary() {
        for (String invalid : List.of("{}", valid().replace("\"goal\":", "\"extra\":0,\"goal\":"), valid().replace("\"goal\":", "\"goal\":\"first\",\"goal\":"),
            valid() + " {}", valid().replace("\"changes\":[]", "\"changes\":null"), valid().replace("\"tests failed\"", "\"\\u0000\""))) {
            assertThatThrownBy(() -> codec.prepare(AiMessage.from(invalid), 2048, List.of(id), null)).isInstanceOf(BusinessException.class)
                .hasMessage("COPILOT_SUMMARY_INVALID");
        }
    }
    @Test void rejectsInventedReferencesOversizeResponsesAndToolRequests() {
        assertThatThrownBy(() -> codec.prepare(AiMessage.from(valid()), 2048, List.of(UUID.randomUUID().toString()), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.prepare(AiMessage.from(valid()), 50, List.of(id), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.prepare(AiMessage.from(ToolExecutionRequest.builder().id("tool").name("run_tests").arguments("{}").build()),
            2048, List.of(id), null)).isInstanceOf(BusinessException.class);
        assertThat(codec.prepare(AiMessage.from(valid()), 2048, List.of(), id)).contains(id);
    }
}
