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
    private final ai.codecraft.modules.modelaccess.service.ProxyAuditService proxy;
    public ReportTimelineReader(ReportTimelineRepository repository, CopilotReportTraceReader copilot,
        ai.codecraft.modules.modelaccess.service.ProxyAuditService proxy) {
        this.repository = repository; this.copilot = copilot; this.proxy = proxy;
    }
    public List<ReportTimelineItemDTO> timeline(String exercise) {
        var events = new java.util.ArrayList<>(repository.timeline(exercise));
        for (var event : proxy.events(exercise)) {
            String duration = event.completedAt() == null ? "UNKNOWN" : Long.toString(java.time.Duration.between(event.createdAt(), event.completedAt()).toMillis());
            events.add(new ReportTimelineItemDTO(event.createdAt(), "CLI_PROXY", "CLI 模型调用 · " + event.endpoint(),
                event.state() + " · ms=" + duration + " · requestBytes=" + event.requestBytes() + " · responseBytes=" + event.responseBytes()
                    + " · toolSignals=" + event.toolSignals() + " · reasoningTokens=" + event.reasoningTokens(), null, event.id()));
        }
        events.sort(java.util.Comparator.comparing(ReportTimelineItemDTO::timestamp).thenComparing(ReportTimelineItemDTO::sourceId));
        return List.copyOf(events);
    }
    public ReportContextTraceDTO contextTrace(String exercise) {
        var trace = copilot.read(exercise);
        return new ReportContextTraceDTO(trace.referenceCount(), trace.skillLoadCount(), trace.steerCount(), trace.btwTaskCount(),
            repository.successfulInteractions(exercise, "CLARIFY"), repository.successfulInteractions(exercise, "DEFENSE"), repository.hints(exercise));
    }
}
