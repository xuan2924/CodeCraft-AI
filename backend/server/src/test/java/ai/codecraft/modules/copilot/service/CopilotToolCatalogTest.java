package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.copilot.model.dto.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

class CopilotToolCatalogTest {
    @Test void advertisesModernSchemasButExecutesLegacyAliasesWithTheSamePermissions() {
        assertThat(CopilotToolCatalog.schemas("MAIN")).extracting(tool -> tool.name()).containsExactlyInAnyOrder(
            "read", "grep", "find", "edit", "write", "run_tests", "bash", "load_skill", "read_source", "read_evidence", "delegate_task", "web_search", "web_read");
        assertThat(CopilotToolCatalog.find("read")).isSameAs(CopilotToolCatalog.find("view_file"));
        assertThat(CopilotToolCatalog.schemas("BTW")).extracting(tool -> tool.name()).doesNotContain("edit", "write", "bash", "run_tests", "delegate_task");
        assertThat(CopilotToolCatalog.schemas("CHILD")).extracting(tool -> tool.name()).doesNotContain("delegate_task");
        assertThat(CopilotToolCatalog.find("read").tracksProjectInstructions()).isTrue();
        assertThat(CopilotToolCatalog.find("edit").preparationCredit()).isEqualTo(CopilotProposalPreparation.PREPARATION_CREDIT);
        assertThatThrownBy(() -> CopilotToolCatalog.find("read").lanes().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
        for (String tool : new String[] {"edit", "write", "run_tests", "bash"}) {
            var schema = (JsonObjectSchema) CopilotToolCatalog.find(tool).schema().parameters();
            assertThat(schema.required()).doesNotContain("expected_revision");
            assertThat(schema.properties()).doesNotContainKey("expected_revision");
        }
    }

    @Test void completionIsNotBusinessSuccess() throws Exception {
        var json = new ObjectMapper();
        assertThat(ToolOutcome.classify(json.readTree("{\"code\":\"INVALID_ARGUMENTS\"}"))).isEqualTo(ToolOutcome.Code.INVALID_ARGUMENTS);
        assertThat(ToolOutcome.classify(json.readTree("{\"code\":\"REVISION_CONFLICT\"}"))).isEqualTo(ToolOutcome.Code.CONFLICT);
        assertThat(ToolOutcome.classify(json.readTree("{\"code\":\"APPROVAL_REJECTED\"}"))).isEqualTo(ToolOutcome.Code.APPROVAL_REJECTED);
        assertThat(ToolOutcome.classify(json.readTree("{\"code\":\"PATH_NOT_ALLOWED\"}"))).isEqualTo(ToolOutcome.Code.DENIED);
        assertThat(ToolOutcome.classify(json.readTree("{\"code\":\"OK\"}"))).isEqualTo(ToolOutcome.Code.OK);
        assertThat(new ToolOutcome.Suspended().waiting()).isTrue();
        assertThat(new ToolOutcome.Completed(ToolOutcome.Code.CONFLICT, "{}").waiting()).isFalse();
    }
}
