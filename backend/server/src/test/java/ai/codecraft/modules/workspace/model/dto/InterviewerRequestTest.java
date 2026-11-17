package ai.codecraft.modules.workspace.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InterviewerRequestTest {
    @Test
    void textOnlyRequestsKeepBackwardCompatibleDefaults() throws Exception {
        var request = new ObjectMapper().readValue("{\"text\":\"question\"}", InterviewerRequest.class);
        assertThat(request).isEqualTo(new InterviewerRequest("question", "CLARIFY", "general"));
    }
    @Test
    void snapshotsExposeExactStatefulContractWithoutInternalAuthorization() {
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        var snapshot = new InterviewerTaskResponse("task", "exercise", "question", "answer", "SUCCEEDED", null,
            "platform", "ACTIVE", "CHALLENGE", "HINT", "rounding", 2, "7", Instant.EPOCH, Instant.EPOCH);
        var node = json.valueToTree(snapshot);
        assertThat(node.size()).isEqualTo(15);
        assertThat(node.path("phase").asText()).isEqualTo("ACTIVE");
        assertThat(node.path("stage").asText()).isEqualTo("CHALLENGE");
        assertThat(node.path("hintLevel").asInt()).isEqualTo(2);
        assertThat(node.path("revision").isTextual()).isTrue();
        assertThat(node.has("sessionHash")).isFalse();
        assertThat(node.has("controlHash")).isFalse();
    }
}
