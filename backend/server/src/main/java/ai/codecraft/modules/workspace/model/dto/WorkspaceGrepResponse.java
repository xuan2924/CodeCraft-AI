package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record WorkspaceGrepResponse(String revision, List<String> lines, boolean truncated) {}
