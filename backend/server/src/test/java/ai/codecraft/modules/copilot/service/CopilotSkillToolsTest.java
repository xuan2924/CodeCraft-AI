package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotSourceDTO;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotSkillToolsTest {
    private static final String SKILL = "10000000-0000-0000-0000-000000000001";
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotExecutionStore execution = mock(CopilotExecutionStore.class);
    private final SkillSnapshots snapshots = mock(SkillSnapshots.class);
    private final CopilotSkillTools tools = new CopilotSkillTools(execution, snapshots, json);

    @Test
    void slicesUtf8WithoutSplittingCharactersAndRejectsPathsOutsideTheSnapshotManifest() throws Exception {
        var task = mock(CopilotTaskEntity.class); when(task.id()).thenReturn("task"); when(task.userId()).thenReturn("owner");
        when(execution.current("task", "worker")).thenReturn(task);
        when(snapshots.source("owner", "task", SKILL)).thenReturn(new SkillSnapshotSourceDTO("immutable-version", json.writeValueAsString(
            new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "Main"), new SkillFileDTO("references/rules.txt", "界".repeat(100)))))));
        var result = json.readTree(tools.execute("task", "worker", args("references/rules.txt", 0)));
        assertThat(result.path("content").asText()).isEqualTo("界".repeat(42));
        assertThat(result.path("nextOffset").asInt()).isEqualTo(126);
        assertThat(result.path("totalBytes").asInt()).isEqualTo(300);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("versionId").asText()).isEqualTo("immutable-version");
        assertThat(tools.execute("task", "worker", args("references/rules.txt", 1))).contains("COPILOT_INVALID_REQUEST");
        for (String path : List.of("references/../SKILL.md", "/etc/passwd", "references/missing.txt", "https://example.test/secret")) {
            assertThat(tools.execute("task", "worker", args(path, 0))).contains("COPILOT_INVALID_REQUEST");
        }
        assertThat(tools.execute("task", "worker", "{\"skill_id\":\"" + SKILL + "\",\"limit\":1.5}")).contains("COPILOT_INVALID_REQUEST");
    }
    @Test
    void anInactiveOrForeignWorkerCannotReadSnapshotContent() throws Exception {
        assertThat(tools.execute("task", "foreign", args("SKILL.md", 0))).contains("COPILOT_NOT_FOUND");
        verify(snapshots, never()).source(any(), any(), any());
    }
    private String args(String path, int offset) {
        return "{\"skill_id\":\"" + SKILL + "\",\"path\":\"" + path + "\",\"offset\":" + offset + ",\"limit\":128}";
    }
}
