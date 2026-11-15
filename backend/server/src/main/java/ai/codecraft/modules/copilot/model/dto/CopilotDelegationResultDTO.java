package ai.codecraft.modules.copilot.model.dto;

/** Bounded handoff, not a replay of the child's tools or a promise that its changes were reverted. */
public record CopilotDelegationResultDTO(String childTaskId, String conversationId, String state, String reason,
    String outputSourceId, String output, boolean outputTruncated, String observedRevision, String notice) { }
