package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.repository.CopilotRetentionRepository;
import ai.codecraft.modules.workspace.service.ExerciseDataContributor;
import ai.codecraft.modules.workspace.model.dto.WorkspaceChangeContext;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Called under the exercise owner's transaction. Personal model configurations are never touched. */
@Service
public class CopilotExerciseData implements ExerciseDataContributor {
    private final CopilotRetentionRepository repository;
    public CopilotExerciseData(CopilotRetentionRepository repository) { this.repository = repository; }
    @Override public long retainedBytes(String exerciseId) { return repository.bytes(exerciseId); }
    @Override public boolean quiescent(String exerciseId) { return repository.quiescent(exerciseId); }
    @Override public Set<String> protectedTestRecords(String exerciseId) { return repository.protectedTestRecords(exerciseId); }
    @Override public Set<String> protectedCommandRecords(String exerciseId) { return repository.protectedCommandRecords(exerciseId); }
    @Override public WorkspaceChangeContext changeContext(String exerciseId, String operationId) { return repository.changeContext(exerciseId, operationId); }
    @Override public void erase(String exerciseId) {
        repository.deleteContext(exerciseId);
        repository.deleteCompactionCalls(exerciseId);
        repository.deleteCompactions(exerciseId);
        repository.deleteSteers(exerciseId);
        repository.deleteFollowups(exerciseId);
        repository.deleteFollowupResumes(exerciseId);
        repository.deleteCommands(exerciseId);
        repository.deleteTests(exerciseId);
        repository.deleteProposals(exerciseId);
        repository.deleteTools(exerciseId);
        repository.deleteForkMessages(exerciseId);
        repository.deleteMessages(exerciseId);
        repository.deleteTurns(exerciseId);
        repository.deleteTasks(exerciseId);
        repository.deleteBtw(exerciseId);
        repository.deleteConversations(exerciseId);
        repository.deleteApprovalChanges(exerciseId);
        repository.deleteApprovalMode(exerciseId);
    }
}
