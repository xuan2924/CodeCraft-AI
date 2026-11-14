package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded discovery; failed transactions remain discoverable on the next pass. */
@Component
public class ModelCheckRetention {
    private static final Logger LOG = LoggerFactory.getLogger(ModelCheckRetention.class);
    private final ModelCheckRepository checks;
    private final ModelCheckRetentionService service;
    private final Clock clock;

    public ModelCheckRetention(ModelCheckRepository checks, ModelCheckRetentionService service, Clock clock) {
        this.checks = checks; this.service = service; this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000)
    public void clean() {
        try {
            for (var id : checks.retentionCandidates(clock.instant().minus(ModelCheckRetentionService.RETENTION))) service.deleteExpired(id);
        } catch (RuntimeException unavailable) {
            LOG.warn("Model check retention unavailable ({})", unavailable.getClass().getSimpleName());
        }
    }
}
