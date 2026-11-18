package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.workspace.service.StorageCapacityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the audit intent with owner revocation/deletion; never performs network I/O. */
@Service
public class ProxyAdmissionStore {
    private final ProxyTokens tokens;
    private final ProxyAuditService audit;
    private final StorageCapacityStore capacity;
    public ProxyAdmissionStore(ProxyTokens tokens, ProxyAuditService audit, StorageCapacityStore capacity) {
        this.tokens = tokens; this.audit = audit; this.capacity = capacity;
    }
    @Transactional
    public String begin(String token, String endpoint, int bytes, String price) {
        var grant = tokens.verify(token);
        capacity.admitAudit(grant.exercise(), 8192L + price.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return audit.start(grant.exercise(), endpoint, bytes, price);
    }
}
