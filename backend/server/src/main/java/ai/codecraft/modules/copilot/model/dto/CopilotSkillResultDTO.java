package ai.codecraft.modules.copilot.model.dto;

public record CopilotSkillResultDTO(String code, String skillId, String versionId, String path, int offset,
    Integer nextOffset, int totalBytes, boolean truncated, String content) {
    @Override public String toString() { return "CopilotSkillResultDTO[REDACTED]"; }
}
