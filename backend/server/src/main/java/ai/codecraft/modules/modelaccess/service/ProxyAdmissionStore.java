package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the audit intent with owner revocation/deletion; never performs network I/O. */
@Service
public class ProxyAdmissionStore {
    private final ProxyTokens tokens;
    private final ProxyAuditService audit;
    public ProxyAdmissionStore(ProxyTokens tokens, ProxyAuditService audit) { this.tokens = tokens; this.audit = audit; }
    @Transactional
    public String begin(String token, String endpoint, int bytes, String price) {
        var grant = tokens.verify(token);
        return audit.start(grant.exercise(), endpoint, bytes, price);
    }
}
