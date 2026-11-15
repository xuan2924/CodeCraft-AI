package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotWebResultDTO(String code, String kind, String input, List<CopilotWebSourceDTO> sources,
    String serviceCredits, boolean usageUnknown, String notice) {
    public CopilotWebResultDTO { sources = List.copyOf(sources); }
}
