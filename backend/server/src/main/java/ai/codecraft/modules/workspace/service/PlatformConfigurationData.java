package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import org.springframework.stereotype.Component;

@Component
public class PlatformConfigurationData implements ExerciseDataContributor {
    private final PlatformBindingService bindings;
    public PlatformConfigurationData(PlatformBindingService bindings) { this.bindings = bindings; }
    @Override public long retainedBytes(String exerciseId) { return bindings.retainedBytes(exerciseId); }
    @Override public boolean quiescent(String exerciseId) { return true; }
    @Override public void erase(String exerciseId) { bindings.erase(exerciseId); }
}
