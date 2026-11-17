package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotReportTraceDTO;
import ai.codecraft.modules.copilot.repository.CopilotReportTraceRepository;
import org.springframework.stereotype.Service;

/** Trusted report aggregation after the caller authorizes exercise ownership; not a public HTTP endpoint. */
@Service
public class CopilotReportTraceReader {
    private final CopilotReportTraceRepository repository;
    public CopilotReportTraceReader(CopilotReportTraceRepository repository) { this.repository = repository; }
    public CopilotReportTraceDTO read(String exercise) { return repository.trace(exercise); }
}
