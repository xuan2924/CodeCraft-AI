package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

/** A reference snapshot fenced by the current official revision. */
public record WorkspaceCollectionPlan(String workspaceInstanceId, long revision, List<Long> retainedRevisions) {}
