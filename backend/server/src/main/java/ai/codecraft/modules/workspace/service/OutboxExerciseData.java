package ai.codecraft.modules.workspace.service;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import org.springframework.stereotype.Service;

@Service
public class OutboxExerciseData implements ExerciseDataContributor {
    private final OutboxStore outbox;
    public OutboxExerciseData(OutboxStore outbox) { this.outbox = outbox; }
    @Override public long retainedBytes(String exercise) { return outbox.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return !outbox.active(exercise); }
    @Override public void erase(String exercise) { outbox.retire(exercise); }
}
