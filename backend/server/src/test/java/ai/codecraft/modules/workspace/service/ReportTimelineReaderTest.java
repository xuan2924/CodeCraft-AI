package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.model.dto.CopilotReportTraceDTO;
import ai.codecraft.modules.copilot.service.CopilotReportTraceReader;
import ai.codecraft.modules.workspace.model.dto.ReportHintUsageDTO;
import ai.codecraft.modules.workspace.model.dto.ReportTimelineItemDTO;
import ai.codecraft.modules.workspace.repository.ReportTimelineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTimelineReaderTest {
    @Test
    void objectiveTraceAndTimelineKeepExactCrossEndContract() throws Exception {
        var repository = mock(ReportTimelineRepository.class);
        var copilot = mock(CopilotReportTraceReader.class);
        var reader = new ReportTimelineReader(repository, copilot);
        when(copilot.read("exercise")).thenReturn(new CopilotReportTraceDTO(2, 1, 1, 1));
        when(repository.successfulInteractions("exercise", "CLARIFY")).thenReturn(2L);
        when(repository.successfulInteractions("exercise", "DEFENSE")).thenReturn(1L);
        when(repository.hints("exercise")).thenReturn(List.of(new ReportHintUsageDTO("rounding", 2)));
        var event = new ReportTimelineItemDTO(Instant.EPOCH, "WORKSPACE_CHANGED", "r0 → r1", "COPILOT_PATCH", "1", "change");
        when(repository.timeline("exercise")).thenReturn(List.of(event));
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        var trace = json.valueToTree(reader.contextTrace("exercise"));
        assertThat(trace.size()).isEqualTo(7);
        assertThat(trace.path("referenceCount").asLong()).isEqualTo(2);
        assertThat(trace.path("skillLoadCount").asLong()).isEqualTo(1);
        assertThat(trace.path("steerCount").asLong()).isEqualTo(1);
        assertThat(trace.path("btwTaskCount").asLong()).isEqualTo(1);
        assertThat(trace.path("interviewerClarifyCount").asLong()).isEqualTo(2);
        assertThat(trace.path("interviewerDefenseCount").asLong()).isEqualTo(1);
        assertThat(trace.path("hintsUsed").get(0).path("maxLevel").asInt()).isEqualTo(2);
        var timeline = json.valueToTree(reader.timeline("exercise")).get(0);
        assertThat(timeline.size()).isEqualTo(6);
        assertThat(timeline.path("revision").isTextual()).isTrue();
        assertThat(timeline.path("sourceId").asText()).isEqualTo("change");
    }
}
