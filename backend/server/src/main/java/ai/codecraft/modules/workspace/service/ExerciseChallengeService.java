package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.ExerciseChallengeResponse;
import org.springframework.stereotype.Service;

@Service
public class ExerciseChallengeService {
    private final ExerciseChallengeStore store;
    private final StorageCapacityService capacity;
    public ExerciseChallengeService(ExerciseChallengeStore store, StorageCapacityService capacity) { this.store = store; this.capacity = capacity; }
    public ExerciseChallengeResponse find(String session, String exercise) { return store.find(session, exercise); }
    public ExerciseChallengeResponse enter(String session, String exercise, String key, String credential) {
        if (store.existing(session, exercise, key)) return store.find(session, exercise);
        return capacity.admit(session, exercise, measurement -> store.enter(session, exercise, key, credential, measurement));
    }
}
