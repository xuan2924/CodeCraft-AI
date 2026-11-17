package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopilotDelegationServiceTest {
    @Test
    void invalidOrOverBudgetArgumentsNeverAdmitAChild() throws Exception {
        var store = mock(CopilotDelegationStore.class);
        var capacity = mock(StorageCapacityService.class);
        var json = new ObjectMapper();
        var service = new CopilotDelegationService(store, capacity, json);
        var parent = mock(CopilotTaskEntity.class);
        for (String input : List.of("{", "null", "[]", "{}", "{\"goal\":42}", "{\"goal\":\" \"}",
            "{\"goal\":\"a\",\"mode\":\"WRITE\"}", "{\"goal\":\"a\",\"mode\":null}", "{\"goal\":\"a\",\"unknown\":true}",
            "{\"goal\":\"a\",\"mode\":\"EXPLORE\",\"mode\":\"EXECUTE\"}", "{\"goal\":\"a\"}{}",
            json.writeValueAsString(Map.of("goal", "汉".repeat(1366))),
            json.writeValueAsString(Map.of("goal", "bounded", "context", "🧩".repeat(2049))),
            json.writeValueAsString(Map.of("goal", "nul\0goal")))) {
            var tool = new CopilotToolEntity("tool", "turn", "parent", "exercise", 0, "call", "delegate_task", input,
                "RUNNING", null, Instant.EPOCH, null);
            var result = service.execute(parent, "worker", tool);
            assertThat(result.waiting()).isFalse();
            assertThat(result.body()).contains("\"childCreated\":false");
        }
        verifyNoInteractions(store, capacity, parent);
    }

    @Test
    void policyKeepsOneLevelAndNeverUpgradesTheReadOnlySideLane() {
        assertThat(CopilotToolPolicy.allows("MAIN", "delegate_task")).isTrue();
        assertThat(CopilotToolPolicy.allows("CHILD", "delegate_task")).isFalse();
        for (String tool : List.of("propose_changes", "run_tests", "bash")) {
            assertThat(CopilotToolPolicy.allows("CHILD", tool)).isTrue();
            assertThat(CopilotToolPolicy.allows("BTW", tool)).isFalse();
        }
        assertThat(CopilotToolPolicy.allows("BTW", "delegate_task")).isFalse();
        for (String tool : List.of("edit", "write", "propose_changes", "bash", "run_tests", "delegate_task")) {
            assertThat(CopilotToolPolicy.allows("EXPLORE", tool)).isFalse();
        }
        for (String tool : List.of("read", "grep", "find", "read_source", "read_evidence", "load_skill", "web_search", "web_read", "view_file")) {
            assertThat(CopilotToolPolicy.allows("EXPLORE", tool)).isTrue();
        }
        assertThat(CopilotToolPolicy.allows("unknown", "view_file")).isFalse();
    }
}
