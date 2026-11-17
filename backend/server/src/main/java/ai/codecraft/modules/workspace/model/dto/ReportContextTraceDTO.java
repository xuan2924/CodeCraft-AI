package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record ReportContextTraceDTO(long referenceCount, long skillLoadCount, long steerCount, long btwTaskCount,
    long interviewerClarifyCount, long interviewerDefenseCount, List<ReportHintUsageDTO> hintsUsed) {
    public ReportContextTraceDTO { hintsUsed = List.copyOf(hintsUsed); }
}
