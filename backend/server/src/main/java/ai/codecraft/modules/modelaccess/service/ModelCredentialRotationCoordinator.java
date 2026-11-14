package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCredentialRotationProperties;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.dto.ModelCredentialRotationCandidateDTO;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in deployment job. Cursor is disposable: committed wrapping-key identities make restart rescans safe. */
@Component
public class ModelCredentialRotationCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(ModelCredentialRotationCoordinator.class);
    private final boolean enabled;
    private final String activeKey;
    private final ModelConnectionRepository connections;
    private final ModelCredentialRotationStore store;
    private String after;

    public ModelCredentialRotationCoordinator(ModelCredentialRotationProperties rotation, ModelSecurityProperties security,
        ModelConnectionRepository connections, ModelCredentialRotationStore store) {
        enabled = rotation.enabled(); activeKey = security.activeKeyId(); this.connections = connections; this.store = store;
        if (enabled && activeKey.isEmpty()) throw new IllegalArgumentException("Credential rotation requires an active encryption key");
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void rotateBatch() {
        if (!enabled) return;
        final List<ModelCredentialRotationCandidateDTO> candidates;
        try { candidates = connections.rotationCandidates(activeKey, after); }
        catch (RuntimeException unavailable) { LOG.warn("Model rotation discovery unavailable ({})", unavailable.getClass().getSimpleName()); return; }
        if (candidates.isEmpty()) { after = null; return; }
        for (var candidate : candidates) {
            try { store.rotate(candidate.userId(), candidate.id()); }
            catch (BusinessException unreadable) {
                // Advance past a bad row so it cannot starve later owners. It is retried on the next sweep.
                LOG.warn("Model rotation candidate rejected ({})", unreadable.getClass().getSimpleName());
            } catch (RuntimeException unavailable) {
                // Stop on infrastructure failure; do not multiply a database timeout by the batch size.
                LOG.warn("Model rotation batch unavailable ({})", unavailable.getClass().getSimpleName()); return;
            }
            after = candidate.id();
        }
    }
}
