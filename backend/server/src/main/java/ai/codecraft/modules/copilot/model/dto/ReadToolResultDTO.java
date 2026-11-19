package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record ReadToolResultDTO(String code, String revision, List<String> lines, boolean truncated, String contentSha256) {
    public ReadToolResultDTO(String code, String revision, List<String> lines, boolean truncated) {
        this(code, revision, lines, truncated, null);
    }
    public ReadToolResultDTO { lines = List.copyOf(lines); }
}
