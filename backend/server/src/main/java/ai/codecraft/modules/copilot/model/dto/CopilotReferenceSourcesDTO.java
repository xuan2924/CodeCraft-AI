package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotReferenceSourcesDTO(List<CopilotReferenceSourceDTO> sources, String nextCursor) {
    public CopilotReferenceSourcesDTO { sources = List.copyOf(sources); }
}
