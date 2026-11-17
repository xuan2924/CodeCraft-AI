package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record ReportTimelineItemDTO(Instant timestamp, String kind, String title, String detail, String revision, String sourceId) {}
