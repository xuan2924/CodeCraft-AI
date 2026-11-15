package ai.codecraft.modules.copilot.model.dto;

public record CopilotEvidenceResultDTO(String code, String id, String kind, String sequence, int offset,
    Integer nextOffset, int totalBytes, boolean truncated, String text) {
    @Override public String toString() { return "CopilotEvidenceResultDTO[code=" + code + ", id=" + id + "]"; }
}
