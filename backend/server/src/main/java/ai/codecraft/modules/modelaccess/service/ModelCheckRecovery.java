package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded recovery discovery; each decision rechecks authority under the owner's transaction lock. */
@Component
public class ModelCheckRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ModelCheckRecovery.class);
    private final ModelCheckRepository checks;
    private final ModelCheckExecutionStore store;
    private final Clock clock;

    public ModelCheckRecovery(ModelCheckRepository checks, ModelCheckExecutionStore store, Clock clock) {
        this.checks = checks; this.store = store; this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        try {
            for (var id : checks.expired(clock.instant())) store.recoverExpired(id);
        } catch (RuntimeException unavailable) {
            LOG.warn("Model check recovery unavailable ({})", unavailable.getClass().getSimpleName());
        }
    }
}
