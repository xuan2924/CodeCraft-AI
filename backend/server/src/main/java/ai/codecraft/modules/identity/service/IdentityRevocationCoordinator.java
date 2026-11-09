package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.entity.RevocationIntentEntity;
import ai.codecraft.modules.identity.model.exception.IdentityRevocationPending;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IdentityRevocationCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityRevocationCoordinator.class);
    private final IdentityTransactionService store;
    private final IdentityRevocationBarrier barrier;

    public IdentityRevocationCoordinator(IdentityTransactionService store, IdentityRevocationBarrier barrier) {
        this.store = store;
        this.barrier = barrier;
    }

    public void prepare(String session, String target, String kind, String key, String fingerprint) {
        RevocationIntentEntity intent = store.beginRevocation(session, target, kind, key, fingerprint);
        if (intent == null || intent.state().equals("FENCED") || intent.state().equals("SUCCEEDED")) return;
        if (!fence(intent)) throw new IdentityRevocationPending(intent.id());
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        for (RevocationIntentEntity intent : store.pendingRevocations()) fence(intent);
    }

    private boolean fence(RevocationIntentEntity intent) {
        try {
            if (intent.state().equals("PENDING")) barrier.fence(List.of(intent.preparationIds().split(",")));
            store.fenced(intent.id());
            return true;
        } catch (RuntimeException error) {
            LOG.warn("Identity revocation requires reconciliation ({})", error.getClass().getSimpleName());
            return false;
        }
    }
}
