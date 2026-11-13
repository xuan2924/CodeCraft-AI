package ai.codecraft.modules.workspace.model.dto;

import ai.codecraft.modules.workspace.model.entity.StorageExecutionEntity;
import java.util.List;

public record StorageScope(String exerciseId, List<String> workspaceInstances, List<StorageExecutionEntity> executions) {}
