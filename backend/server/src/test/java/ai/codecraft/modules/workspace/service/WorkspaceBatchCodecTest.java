package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceBatchCodecTest {
    @Test
    void publishedAgentGuidanceIsImmutableEvenInsideEditableRoots() {
        var codec = new WorkspaceBatchCodec(new ObjectMapper());
        for (String path : List.of("AGENTS.md", "src/main/java/AGENTS.md", "src/main/java/orders/AGENTS.md", "src/test/java/AGENTS.md")) {
            for (String kind : List.of("CREATE", "UPDATE", "DELETE")) {
                assertThatThrownBy(() -> codec.encode(new FileBatchRequest("0", List.of(new FileChangeRequest(path, kind, "")))))
                    .hasMessage("PATH_FORBIDDEN");
            }
        }
        assertThatCode(() -> codec.encode(new FileBatchRequest("0", List.of(new FileChangeRequest("src/main/java/Order.java", "UPDATE", "class Order {}")))))
            .doesNotThrowAnyException();
    }
}
