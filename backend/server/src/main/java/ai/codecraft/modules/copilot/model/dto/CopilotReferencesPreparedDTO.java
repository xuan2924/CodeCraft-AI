package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotReferencesPreparedDTO(List<CopilotReferencePageDTO> pages, String payload) {
    public CopilotReferencesPreparedDTO { pages = List.copyOf(pages); }
    @Override public String toString() { return "CopilotReferencesPreparedDTO[count=" + pages.size() + "]"; }
}
