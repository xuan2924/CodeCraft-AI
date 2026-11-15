package ai.codecraft.modules.workspace.model.dto;

/** Optional originating task/conversation association; it grants no replay authority. */
public record WorkspaceChangeContext(String taskId, String conversationId) {}
