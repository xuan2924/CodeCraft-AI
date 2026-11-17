package ai.codecraft.modules.copilot.model.dto;

/** Offsets refer to the original LF-normalized file, never to incrementally edited text. */
public record CopilotTextReplacementDTO(int start, int end, String text) {}
