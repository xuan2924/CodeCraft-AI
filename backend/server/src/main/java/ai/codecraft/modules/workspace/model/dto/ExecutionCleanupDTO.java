package ai.codecraft.modules.workspace.model.dto;

/** Minimal deletion precondition; no result, log or code body crosses this seam. */
public record ExecutionCleanupDTO(boolean quiescent, String cleanup, String copyCleanup) {}
