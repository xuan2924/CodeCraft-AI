package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

/** Only these already-converged operations may be released by a later measurement. */
public record StorageReconciliationPlan(StorageScope scope, List<String> completedOperations) {}
