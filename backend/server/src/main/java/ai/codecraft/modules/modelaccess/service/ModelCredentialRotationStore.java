package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted deployment maintenance only; no HTTP entry and no user credential delivery. */
@Service
public class ModelCredentialRotationStore {
    private final IdentityTransactionService identity;
    private final ModelConnectionRepository connections;
    private final ModelCredentialCipher cipher;

    public ModelCredentialRotationStore(IdentityTransactionService identity, ModelConnectionRepository connections, ModelCredentialCipher cipher) {
        this.identity = identity; this.connections = connections; this.cipher = cipher;
    }

    @Transactional
    public boolean rotate(String owner, String id) {
        identity.lockResourceOwner(owner);
        var current = connections.find(id);
        if (current == null || !owner.equals(current.userId()) || current.deletedAt() != null) return false;
        var original = current.credential();
        var replacement = cipher.rotate(owner, id, original);
        if (replacement.equals(original)) return false;
        return connections.rotateCredential(id, owner, original, replacement) == 1;
    }
}
