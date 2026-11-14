package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record ReadToolResultDTO(String code, String revision, List<String> lines, boolean truncated) {
    public ReadToolResultDTO { lines = List.copyOf(lines); }
}
