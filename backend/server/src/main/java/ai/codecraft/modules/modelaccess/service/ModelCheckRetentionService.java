package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes only unreferenced terminal check history, never command replay identities. */
@Service
public class ModelCheckRetentionService {
    public static final Duration RETENTION = Duration.ofDays(30);
    private final ModelCheckRepository checks;
    private final IdentityTransactionService identity;
    private final Clock clock;

    public ModelCheckRetentionService(ModelCheckRepository checks, IdentityTransactionService identity, Clock clock) {
        this.checks = checks; this.identity = identity; this.clock = clock;
    }

    @Transactional
    public boolean deleteExpired(String id) {
        var check = checks.find(id);
        if (check == null) return false;
        identity.lockResourceOwner(check.userId());
        if (!checks.retentionEligible(id, clock.instant().minus(RETENTION))) return false;
        checks.deleteReuse(id);
        checks.deleteRequests(id);
        checks.deleteAuthority(id);
        checks.deleteCheck(id);
        return true;
    }
}
