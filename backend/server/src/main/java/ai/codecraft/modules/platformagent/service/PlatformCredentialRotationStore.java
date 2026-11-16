package ai.codecraft.modules.platformagent.service;

import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import ai.codecraft.modules.platformagent.repository.PlatformAgentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformCredentialRotationStore {
    private final PlatformAgentRepository repository;
    private final ModelCredentialCipher cipher;
    private final ModelSecurityProperties security;
    public PlatformCredentialRotationStore(PlatformAgentRepository repository, ModelCredentialCipher cipher, ModelSecurityProperties security) {
        this.repository = repository; this.cipher = cipher; this.security = security;
    }
    @Transactional(readOnly = true)
    public List<String> candidates(String after) { return repository.rotationCandidates(security.activeKeyId(), after); }
    @Transactional
    public void rotate(String id) {
        repository.lockRoles();
        var version = repository.lock(id);
        if (version == null || version.credentialKeyId() == null || version.credentialKeyId().equals(security.activeKeyId())) return;
        var rotated = cipher.rotate("platform", id, new EncryptedCredentialDTO(version.credentialKeyId(), version.credentialNonce(), version.credentialCiphertext()));
        repository.rewrap(id, version.credentialNonce(), rotated);
    }
}
