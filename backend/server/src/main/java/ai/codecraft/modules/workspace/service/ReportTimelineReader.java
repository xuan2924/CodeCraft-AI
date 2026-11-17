package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.copilot.service.CopilotReportTraceReader;
import ai.codecraft.modules.workspace.model.dto.ReportContextTraceDTO;
import ai.codecraft.modules.workspace.model.dto.ReportTimelineItemDTO;
import ai.codecraft.modules.workspace.repository.ReportTimelineRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Called only after report ownership/deletion authorization in ExerciseReportStore. */
@Service
public class ReportTimelineReader {
    private final ReportTimelineRepository repository;
    private final CopilotReportTraceReader copilot;
    public ReportTimelineReader(ReportTimelineRepository repository, CopilotReportTraceReader copilot) {
        this.repository = repository; this.copilot = copilot;
    }
    public List<ReportTimelineItemDTO> timeline(String exercise) { return repository.timeline(exercise); }
    public ReportContextTraceDTO contextTrace(String exercise) {
        var trace = copilot.read(exercise);
        return new ReportContextTraceDTO(trace.referenceCount(), trace.skillLoadCount(), trace.steerCount(), trace.btwTaskCount(),
            repository.successfulInteractions(exercise, "CLARIFY"), repository.successfulInteractions(exercise, "DEFENSE"), repository.hints(exercise));
    }
}
