package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

/** Immutable project guidance prepared for one model request, not an authorization grant. */
public record CopilotProjectInstructionsDTO(String exerciseId, String revision, List<CopilotProjectInstructionDTO> sources, boolean scopeTruncated) {
    public CopilotProjectInstructionsDTO { sources = List.copyOf(sources); }
}
