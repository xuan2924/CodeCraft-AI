package ai.codecraft.modules.workspace.model.dto;

import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;

/** Internal lookup result; a non-acceptance proof requires an irreversible source/baseline barrier. */
public record WorkspaceRestoreResolutionDTO(WorkspaceOperationEntity operation, String notAcceptedReason) {}
