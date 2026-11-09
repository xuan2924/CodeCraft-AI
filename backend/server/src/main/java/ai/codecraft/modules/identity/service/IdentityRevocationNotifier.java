package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.IdentityRevokedEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local close hint only; durable session checks remain authoritative across instances. */
@Service
public final class IdentityRevocationNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityRevocationNotifier.class);
    private final ApplicationEventPublisher publisher;

    public IdentityRevocationNotifier(ApplicationEventPublisher publisher) { this.publisher = publisher; }

    public void afterCommit(String userId, String sessionHash) {
        IdentityRevokedEventDTO event = new IdentityRevokedEventDTO(userId, sessionHash);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { publisher.publishEvent(event); }
                catch (RuntimeException failure) {
                    // A failed hint cannot undo the commit; consumers must also recheck the database.
                    LOG.error("Revocation notification failed ({})", failure.getClass().getSimpleName());
                }
            }
        });
    }
}
